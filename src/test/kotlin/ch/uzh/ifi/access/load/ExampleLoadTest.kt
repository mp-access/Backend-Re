package ch.uzh.ifi.access.load

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionFileDTO
import ch.uzh.ifi.access.repository.SubmissionRepository
import ch.uzh.ifi.access.service.ExampleQueueService
import ch.uzh.ifi.access.service.ExampleService
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Burst load test for the example queue (refactoring plan, step 3: compare before / after).
 *
 * Resets and publishes an example of the imported mock course, then fires LOAD_SUBMISSIONS GRADE submissions
 * of distinct synthetic students at once into [ExampleQueueService.addToQueue] and waits until the queue is
 * idle again. Grading is real (database + pooled Docker containers). Only uses methods that exist unchanged
 * before and after the GradingQueue migration, so the very same file runs against both versions.
 *
 * LOAD_EXAMPLE_PHASE:
 *   drain        (default) the example is terminated right after the burst, like the end of a lecture
 *                exercise: after the grace period the queue switches to drain mode (CPU gate ignored).
 *   interactive  the example stays open: everything runs through the CPU gate (min / max concurrency).
 *
 * WARNING: resets the chosen example in the local database (as the supervisor "reset" button does).
 * Only runs when LOAD_TEST=true. Further settings (environment variables):
 *   LOAD_SUBMISSIONS     number of submissions in the burst        (default 200)
 *   LOAD_EXAMPLE_COURSE  course slug                               (default access-mock-course-lecture-examples)
 *   LOAD_EXAMPLE         example slug                              (default: first example of the course)
 *   LOAD_LABEL           name of the run, e.g. example-baseline    (default example-run)
 *   LOAD_TIMEOUT_MIN     how long to wait for the burst to finish  (default 15)
 * Result: printed summary + build/load-results/<label>-<timestamp>.json
 */
@EnabledIfEnvironmentVariable(named = "LOAD_TEST", matches = "true")
@TestPropertySource(properties = ["docker.pool.enabled=true"])
class ExampleLoadTest(
    @Autowired val exampleQueueService: ExampleQueueService,
    @Autowired val exampleService: ExampleService,
    @Autowired val submissionRepository: SubmissionRepository,
    @Autowired val meterRegistry: MeterRegistry,
    @Autowired val transactionManager: PlatformTransactionManager,
) : BaseTest() {

    private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    @Test
    fun `burst of example submissions`() {
        val submissions = env("LOAD_SUBMISSIONS", "200").toInt()
        val phase = env("LOAD_EXAMPLE_PHASE", "drain")
        val course = env("LOAD_EXAMPLE_COURSE", "access-mock-course-lecture-examples")
        val example = System.getenv("LOAD_EXAMPLE")?.takeIf { it.isNotBlank() }
            ?: exampleService.getExamples(course).firstNotNullOfOrNull { it.slug }
            ?: error("course $course has no examples - is the mock course imported?")
        val label = env("LOAD_LABEL", "example-run")
        val timeoutMin = env("LOAD_TIMEOUT_MIN", "15").toLong()
        val runId = System.currentTimeMillis()

        // Fresh interactive period, long enough for the whole burst.
        exampleService.resetExampleBySlug(course, example)
        exampleService.publishExampleBySlug(course, example, 3600)
        Thread.sleep(100) // the interactive period starts strictly after "start"

        val files: List<SubmissionFileDTO> = TransactionTemplate(transactionManager).execute {
            exampleService.getExampleBySlug(course, example).files
                .filter { it.enabled && it.editable }
                .map { SubmissionFileDTO(it.id, it.template ?: "") }
        }!!
        assertTrue(files.isNotEmpty(), "example $course/$example has no editable files")

        val fallbackBefore = counter("docker.pool.borrow.fallback")
        val users = (1..submissions).map { "load-$runId-$it" }
        val enqueuedAt = HashMap<String, Long>()
        val doneAt = ConcurrentHashMap<String, Long>()
        val rejected = mutableListOf<String>()

        val start = System.nanoTime()
        users.forEach { user ->
            val dto = SubmissionDTO(restricted = true, userId = user, command = Command.GRADE, files = files)
            enqueuedAt[user] = System.nanoTime()
            // Unit before the migration, Boolean after: compare against false to support both.
            val accepted: Any = exampleQueueService.addToQueue(course, example, dto, LocalDateTime.now())
            if (accepted == false) rejected += user
        }
        val enqueueMs = (System.nanoTime() - start) / 1e6
        var terminatedAt: Long? = null
        if (phase == "drain") {
            exampleService.terminateExampleBySlug(course, example)
            terminatedAt = System.nanoTime()
        }

        // Poll until every accepted submission has left the queue. Waiting is checked before running, so the
        // move waiting -> running is never mistaken for "done".
        val pending = (users - rejected.toSet()).toMutableSet()
        var peakRunning = 0.0
        var peakWaiting = 0.0
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMin)
        while (pending.isNotEmpty() && System.nanoTime() < deadline) {
            val now = System.nanoTime()
            pending.removeIf { user ->
                val visible = exampleQueueService.isSubmissionInTheQueue(course, example, user) ||
                    exampleQueueService.isSubmissionCurrentlyProcessed(course, example, user)
                if (!visible) doneAt[user] = now
                !visible
            }
            peakRunning = maxOf(peakRunning, gauge("example_queue.running"))
            peakWaiting = maxOf(peakWaiting, gauge("example_queue.waiting"))
            Thread.sleep(50)
        }
        val totalMs = (System.nanoTime() - start) / 1e6
        val idle = exampleQueueService.areInteractiveExampleSubmissionsFullyProcessed(course, example)

        // Via the repository (not a service) so the test compiles against the baseline as well.
        val period = exampleService.getExampleBySlug(course, example)
        val graded = submissionRepository.findInteractiveExampleSubmissions(
            period.id, Command.GRADE, period.start!!, period.end!!.plusSeconds(60)
        ).count { it.testsPassed.isNotEmpty() && it.userId in enqueuedAt.keys }
        val latencies = doneAt.map { (user, at) -> (at - enqueuedAt.getValue(user)) / 1e6 }.sorted()

        val result = linkedMapOf<String, Any?>(
            "label" to label,
            "timestamp" to LocalDateTime.now().toString(),
            "phase" to phase,
            "example" to "$course/$example",
            "submissions" to submissions,
            "accepted" to submissions - rejected.size,
            "rejected" to rejected.size,
            "graded" to graded,
            "notGraded" to (submissions - rejected.size - graded),
            "unfinishedAtTimeout" to pending.size,
            "queueIdleAtEnd" to idle,
            "enqueueMs" to enqueueMs.round1(),
            "totalMs" to totalMs.round1(),
            "drainMs" to terminatedAt?.let { ((System.nanoTime() - it) / 1e6).round1() },
            "throughputPerSec" to (doneAt.size / (totalMs / 1000)).round1(),
            "latencyMs" to mapOf(
                "p50" to latencies.percentile(0.50),
                "p95" to latencies.percentile(0.95),
                "p99" to latencies.percentile(0.99),
                "max" to latencies.lastOrNull()?.round1(),
            ),
            "peakRunning" to peakRunning,
            "peakWaiting" to peakWaiting,
            "poolBorrowFallbacks" to (counter("docker.pool.borrow.fallback") - fallbackBefore).takeUnless { it.isNaN() },
            "poolBorrowWaitMs" to borrowWait(),
            "queueMetrics" to listOf("failed", "rejected").mapNotNull { name ->
                meterRegistry.find("example_queue.$name").counter()?.let { name to it.count() }
            }.toMap(),
        )

        val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result)
        val out = File("build/load-results").apply { mkdirs() }
            .resolve("$label-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json")
        out.writeText(json)
        println("\n===== LOAD TEST RESULT ($label) =====\n$json\nwritten to ${out.absolutePath}\n")

        assertTrue(pending.isEmpty(), "${pending.size} submissions did not finish within $timeoutMin min")
    }

    // ---------------------------------------------------------------------------------------------

    private fun counter(name: String): Double = meterRegistry.find(name).counter()?.count() ?: Double.NaN

    private fun gauge(name: String): Double = meterRegistry.find(name).gauge()?.value() ?: 0.0

    private fun borrowWait(): Map<String, Any?>? {
        val timer = meterRegistry.find("docker.pool.borrow.wait").timer() ?: return null
        return mapOf(
            "count" to timer.count(),
            "mean" to timer.mean(TimeUnit.MILLISECONDS).round1(),
            "max" to timer.max(TimeUnit.MILLISECONDS).round1(),
            "percentiles" to timer.takeSnapshot().percentileValues().associate {
                "p${(it.percentile() * 100).toInt()}" to it.value(TimeUnit.MILLISECONDS).round1()
            },
        )
    }

    private fun List<Double>.percentile(p: Double): Double? =
        if (isEmpty()) null else this[((size - 1) * p).toInt()].round1()

    private fun Double.round1(): Double = Math.round(this * 10) / 10.0
}
