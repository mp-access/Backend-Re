package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.SubmissionDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One pending submission plus the future that completes when it has been graded. */
private class QueuedSubmission(
    val courseSlug: String,
    val assignmentSlug: String,
    val taskSlug: String,
    val submission: SubmissionDTO,
    val done: CompletableFuture<Unit>,
)

/**
 * Intake buffer for regular task submissions. The controller drops a submission here and returns a
 * DeferredResult instead of blocking a request thread; a fixed pool of worker threads grades from the
 * queue. This decouples "accepted" from "in-flight": a burst of N submissions costs N cheap queue
 * entries, not N parked Tomcat threads, so the endpoint survives well past the servlet thread limit.
 *
 * The queue is BOUNDED on purpose (backpressure): when it is full, enqueue() returns null and the
 * caller sheds load with a 503 instead of accepting unbounded work. Mirrors ExampleQueueService.
 */
@Service
class SubmissionQueueService(
    private val submissionService: SubmissionService,
    @Value("\${submission.queue.capacity:2000}") private val capacity: Int,
    @Value("\${submission.queue.workers:10}") private val workers: Int,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val running = AtomicBoolean(false)
    private lateinit var queue: BlockingQueue<QueuedSubmission>
    private val workerThreads = mutableListOf<Thread>()

    @PostConstruct
    fun start() {
        queue = LinkedBlockingQueue(capacity)
        // Expose how many submissions are waiting to be graded (for the burst/backpressure runs).
        Gauge.builder("submission.queue.depth", this) { it.queueDepth().toDouble() }.register(meterRegistry)
        running.set(true)
        repeat(workers) { i ->
            val thread = Thread({ workLoop() }, "submission-worker-$i")
            thread.isDaemon = true
            thread.start()
            workerThreads.add(thread)
        }
        logger.info { "SubmissionQueueService started: $workers workers, queue capacity $capacity" }
    }

    /**
     * Offer a submission for grading. Returns a future that completes when grading finishes (or
     * completes exceptionally with the grading error), or null if the queue is full -- in which case
     * the caller should respond 503 (backpressure).
     */
    fun enqueue(
        courseSlug: String,
        assignmentSlug: String,
        taskSlug: String,
        submission: SubmissionDTO,
    ): CompletableFuture<Unit>? {
        val done = CompletableFuture<Unit>()
        val queued = QueuedSubmission(courseSlug, assignmentSlug, taskSlug, submission, done)
        if (!queue.offer(queued)) {
            logger.warn { "Submission queue full (capacity $capacity) - rejecting submission for user ${submission.userId}" }
            return null
        }
        return done
    }

    /** Current number of submissions waiting to be graded (observability). */
    fun queueDepth(): Int = if (::queue.isInitialized) queue.size else 0

    private fun workLoop() {
        while (running.get()) {
            val item = try {
                queue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            try {
                // createTaskSubmission is @Transactional and reached cross-bean, so BOTH its caching
                // proxy and a Hibernate session engage on this worker thread. Open-session-in-view does
                // not apply off the request thread, so the transaction is what makes lazy loads work.
                submissionService.createTaskSubmission(
                    item.courseSlug, item.assignmentSlug, item.taskSlug, item.submission
                )
                item.done.complete(Unit)
            } catch (e: Exception) {
                logger.error(e) { "Grading failed for queued submission (user ${item.submission.userId})" }
                item.done.completeExceptionally(e)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down SubmissionQueueService (${queueDepth()} submissions still queued)" }
        running.set(false)
        workerThreads.forEach { it.interrupt() }
    }
}
