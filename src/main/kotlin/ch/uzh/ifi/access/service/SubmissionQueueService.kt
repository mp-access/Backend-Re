package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.queue.FixedPolicy
import ch.uzh.ifi.access.queue.GradingQueue
import ch.uzh.ifi.access.queue.GradingQueueListener
import ch.uzh.ifi.access.queue.RetryPolicy
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CompletableFuture

/** One pending submission plus the future that completes when it has been graded. */
private class QueuedSubmission(
    val courseSlug: String,
    val assignmentSlug: String,
    val taskSlug: String,
    val submission: SubmissionDTO,
    val done: CompletableFuture<Unit>,
)

/**
 * Intake buffer for regular task submissions (peak times, e.g. shortly before a deadline). The controller
 * drops a submission here and returns a DeferredResult instead of blocking a request thread; a fixed number
 * of workers grades from the queue. A burst of N submissions costs N cheap queue entries, not N parked
 * Tomcat threads, so the endpoint survives well past the servlet thread limit.
 *
 * Built on [GradingQueue]:
 *  - at most [workers] gradings run in parallel (keep roughly equal to docker.pool.size),
 *  - BOUNDED on purpose (backpressure): when full, [enqueue] returns null and the caller answers 503,
 *  - a failed grading is retried up to [maxRetries] times, except for 4xx rejections (no attempts left,
 *    unknown task, ...). Safe because createTaskSubmission is transactional: a failed attempt is rolled
 *    back completely and leaves no submission behind.
 */
@Service
class SubmissionQueueService(
    private val submissionService: SubmissionService,
    @Value("\${submission.queue.capacity:2000}") private val capacity: Int,
    @Value("\${submission.queue.workers:10}") workers: Int,
    @Value("\${submission.queue.max-retries:2}") maxRetries: Int,
    meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    private val queue = GradingQueue<QueuedSubmission>(
        name = "submission_queue",
        capacity = capacity,
        policy = FixedPolicy(workers),
        retryPolicy = RetryPolicy.skipClientErrors(maxRetries),
        meterRegistry = meterRegistry,
        handler = ::grade,
        listener = object : GradingQueueListener<QueuedSubmission> {
            override fun onAttemptFailed(item: QueuedSubmission, attempt: Int, error: Exception, willRetry: Boolean) =
                logFailure(item, attempt, error, willRetry)

            override fun onCompleted(item: QueuedSubmission, error: Exception?) {
                // Hand the original exception to the controller, so e.g. 403 / 404 keep their meaning.
                if (error == null) item.done.complete(Unit) else item.done.completeExceptionally(error)
            }
        },
    )

    init {
        // Kept from before the GradingQueue migration, for existing dashboards and the burst runs.
        Gauge.builder("submission.queue.depth", queue) { it.waitingCount.toDouble() }.register(meterRegistry)
    }

    @PostConstruct
    fun start() {
        queue.start()
    }

    @PreDestroy
    fun shutdown() {
        queue.shutdown()
    }

    /**
     * Offer a submission for grading. Returns a future that completes when grading finishes (or completes
     * exceptionally with the grading error), or null if the queue is full -- in which case the caller
     * should respond 503 (backpressure).
     */
    fun enqueue(
        courseSlug: String,
        assignmentSlug: String,
        taskSlug: String,
        submission: SubmissionDTO,
    ): CompletableFuture<Unit>? {
        val done = CompletableFuture<Unit>()
        if (!queue.offer(QueuedSubmission(courseSlug, assignmentSlug, taskSlug, submission, done))) {
            logger.warn { "Submission queue full (capacity $capacity) - rejecting submission for user ${submission.userId}" }
            return null
        }
        return done
    }

    /** Current number of submissions waiting to be graded (observability). */
    fun queueDepth(): Int = queue.waitingCount

    private fun grade(item: QueuedSubmission) {
        // createTaskSubmission is @Transactional and reached cross-bean, so BOTH its caching proxy and a
        // Hibernate session engage on this worker thread. Open-session-in-view does not apply off the
        // request thread, so the transaction is what makes lazy loads work.
        submissionService.createTaskSubmission(item.courseSlug, item.assignmentSlug, item.taskSlug, item.submission)
    }

    private fun logFailure(item: QueuedSubmission, attempt: Int, error: Exception, willRetry: Boolean) {
        val who = "user ${item.submission.userId}, task ${item.courseSlug}/${item.assignmentSlug}/${item.taskSlug}"
        when {
            willRetry ->
                logger.warn(error) { "Grading attempt $attempt failed ($who). Retrying." }
            error is ResponseStatusException && error.statusCode.is4xxClientError ->
                logger.info { "Submission rejected ($who): ${error.reason}" }
            else ->
                logger.error(error) { "Grading failed after $attempt attempt(s) ($who)." }
        }
    }
}
