package ch.uzh.ifi.access.load

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionFileDTO
import ch.uzh.ifi.access.service.SubmissionQueueService
import ch.uzh.ifi.access.service.SubmissionService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Burst load test for the submission queue (refactoring plan, step 2: compare before / after).
 *
 * Fires LOAD_SUBMISSIONS submissions at once into [SubmissionQueueService.enqueue] and waits until all are
 * graded for real (database + pooled Docker containers). Only uses enqueue() and queueDepth(), which exist
 * unchanged before and after the GradingQueue migration, so the very same file runs against both versions.
 *
 * Bypasses the HTTP controller on purpose: it is unchanged by the refactoring, and going through it would
 * require real Keycloak users. Every submission gets its own synthetic user id, like a real burst.
 *
 * Only runs when LOAD_TEST=true. Requires the mock course in the database (run the full suite once before).
 * Settings (environment variables):
 *   LOAD_SUBMISSIONS  number of submissions in the burst          (default 200)
 *   LOAD_COMMAND      grade | run | test                          (default grade)
 *   LOAD_TASK         course/assignment/task                      (default access-mock-course/basics/variable-assignment)
 *   LOAD_LABEL        name of the run, e.g. baseline or new       (default run)
 *   LOAD_TIMEOUT_MIN  how long to wait for the burst to finish    (default 15)
 * Result: printed summary + build/load-results/<label>-<timestamp>.json
 */
@EnabledIfEnvironmentVariable(named = "LOAD_TEST", matches = "true")
@TestPropertySource(properties = ["docker.pool.enabled=true"])
class SubmissionLoadTest(
    @Autowired val submissionQueueService: SubmissionQueueService,
    @Autowired val submissionService: SubmissionService,
    @Autowired val meterRegistry: MeterRegistry,
    @Autowired val transactionManager: PlatformTransactionManager,
) : BaseTest() {

    private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private data class Outcome(val latencyMs: Double, val error: String?)

    @Test
    fun `burst of submissions`() {
        val submissions = env("LOAD_SUBMISSIONS", "200").toInt()
        val command = Command.valueOf(env("LOAD_COMMAND", "grade").uppercase())
        val (course, assignment, task) = env("LOAD_TASK", "access-mock-course/basics/variable-assignment").split("/")
        val label = env("LOAD_LABEL", "run")
        val timeoutMin = env("LOAD_TIMEOUT_MIN", "15").toLong()
        val runId = System.currentTimeMillis()

        // Editable files of the task with their templates, i.e. what a student submits unchanged.
        val files: List<SubmissionFileDTO> = TransactionTemplate(transactionManager).execute {
            submissionService.getTaskBySlug(course, assignment, task).files
                .filter { it.enabled && it.editable }
                .map { SubmissionFileDTO(it.id, it.template ?: "") }
        }!!
        assertTrue(files.isNotEmpty(), "task $course/$assignment/$task has no editable files - is the mock course imported?")

        val fallbackBefore = counter("docker.pool.borrow.fallback")

        // Sample the queue depth while the burst is processed.
        val sampling = AtomicBoolean(true)
        val peakDepth = AtomicInteger()
        val sampler = thread(name = "load-sampler") {
            while (sampling.get()) {
                peakDepth.accumulateAndGet(submissionQueueService.queueDepth()) { a, b -> maxOf(a, b) }
                Thread.sleep(50)
            }
        }

        val outcomes = ConcurrentLinkedQueue<Outcome>()
        var rejected = 0
        val start = System.nanoTime()
        val futures = (1..submissions).mapNotNull { i ->
            val dto = SubmissionDTO(restricted = true, userId = "load-$runId-$i", command = command, files = files)
            val enqueuedAt = System.nanoTime()
            val future = submissionQueueService.enqueue(course, assignment, task, dto)
            if (future == null) {
                rejected++
                null
            } else {
                future.whenComplete { _, error ->
                    outcomes.add(Outcome((System.nanoTime() - enqueuedAt) / 1e6, error?.let { it::class.simpleName + ": " + it.message }))
                }
            }
        }
        val enqueueMs = (System.nanoTime() - start) / 1e6

        CompletableFuture.allOf(*futures.toTypedArray())
            .handle { _, _ -> Unit } // failures are counted per submission, not thrown here
            .get(timeoutMin, TimeUnit.MINUTES)
        val totalMs = (System.nanoTime() - start) / 1e6
        sampling.set(false)
        sampler.join()

        val latencies = outcomes.map { it.latencyMs }.sorted()
        val failed = outcomes.filter { it.error != null }
        val result = linkedMapOf<String, Any?>(
            "label" to label,
            "timestamp" to LocalDateTime.now().toString(),
            "submissions" to submissions,
            "command" to command.name,
            "task" to "$course/$assignment/$task",
            "accepted" to futures.size,
            "rejected" to rejected,
            "succeeded" to outcomes.size - failed.size,
            "failed" to failed.size,
            "failureSamples" to failed.map { it.error }.distinct().take(5),
            "enqueueMs" to enqueueMs.round1(),
            "totalMs" to totalMs.round1(),
            "throughputPerSec" to (outcomes.size / (totalMs / 1000)).round1(),
            "latencyMs" to mapOf(
                "p50" to latencies.percentile(0.50),
                "p95" to latencies.percentile(0.95),
                "p99" to latencies.percentile(0.99),
                "max" to latencies.lastOrNull()?.round1(),
            ),
            "peakQueueDepth" to peakDepth.get(),
            "poolBorrowFallbacks" to (counter("docker.pool.borrow.fallback") - fallbackBefore).takeUnless { it.isNaN() },
            "poolBorrowWaitMs" to borrowWait(),
            "queueMetrics" to queueMetrics(),
        )

        val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result)
        val out = File("build/load-results").apply { mkdirs() }
            .resolve("$label-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json")
        out.writeText(json)
        println("\n===== LOAD TEST RESULT ($label) =====\n$json\nwritten to ${out.absolutePath}\n")

        assertTrue(outcomes.size == futures.size, "not all accepted submissions completed")
    }

    // ---------------------------------------------------------------------------------------------

    private fun counter(name: String): Double = meterRegistry.find(name).counter()?.count() ?: Double.NaN

    private fun borrowWait(): Map<String, Any?>? {
        val timer = meterRegistry.find("docker.pool.borrow.wait").timer() ?: return null
        val snapshot = timer.takeSnapshot()
        return mapOf(
            "count" to timer.count(),
            "mean" to timer.mean(TimeUnit.MILLISECONDS).round1(),
            "max" to timer.max(TimeUnit.MILLISECONDS).round1(),
            "percentiles" to snapshot.percentileValues().associate {
                "p${(it.percentile() * 100).toInt()}" to it.value(TimeUnit.MILLISECONDS).round1()
            },
        )
    }

    /** submission_queue.* only exists after the GradingQueue migration; empty for the baseline. */
    private fun queueMetrics(): Map<String, Double> =
        listOf("failed", "rejected").mapNotNull { name ->
            meterRegistry.find("submission_queue.$name").counter()?.let { name to it.count() }
        }.toMap()

    private fun List<Double>.percentile(p: Double): Double? =
        if (isEmpty()) null else this[((size - 1) * p).toInt()].round1()

    private fun Double.round1(): Double = Math.round(this * 10) / 10.0
}
