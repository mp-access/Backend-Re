package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.config.ExampleQueueProperties
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.SubmissionWithContext
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.queue.CpuGatedPolicy
import ch.uzh.ifi.access.queue.DrainAwarePolicy
import ch.uzh.ifi.access.queue.GradingQueue
import ch.uzh.ifi.access.queue.GradingQueueListener
import ch.uzh.ifi.access.queue.RetryPolicy
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Queue for GRADE submissions made during the interactive period of an example, built on [GradingQueue].
 *
 * Dispatch rules ([CpuGatedPolicy] wrapped in a [DrainAwarePolicy]):
 *  - During the interactive period: always at least min-concurrent-submissions run, regardless of CPU load
 *    (progress guarantee). Above that, up to max-concurrent-submissions run while CPU usage is below cpu-threshold.
 *  - Drain mode (the example of the queue head is no longer interactive, i.e. end + grace period has passed or the
 *    example was terminated): the CPU gate is ignored and up to drain-concurrent-submissions run until the queue
 *    is empty. No new entries arrive after the end, so the queue only shrinks.
 *
 * Failed gradings are retried up to max-retries times, except for 4xx rejections (too late, submission lock, ...).
 * All settings: [ExampleQueueProperties].
 * When the queue is full, [addToQueue] returns false and the caller rejects the submission.
 */
@Service
class ExampleQueueService(
    private val exampleService: ExampleService,
    private val exampleSubmissionService: ExampleSubmissionService,
    private val embeddingQueueService: EmbeddingQueueService,
    meterRegistry: MeterRegistry,
    cpuUsageProvider: CpuUsageProvider,
    private val clock: Clock,
    private val properties: ExampleQueueProperties,
) {
    private val logger = KotlinLogging.logger {}
    private val interactiveCacheMillis = 2_000L
    private val busyLogIntervalMillis = 10_000L

    // (course, example) -> (isInteractive, checkedAtMillis); avoids a DB lookup on every dispatch check.
    private val interactiveCache = ConcurrentHashMap<Pair<String, String>, Pair<Boolean, Long>>()

    // (course, example) -> drain start in millis, for logging how long draining took.
    private val drainStartedAt = ConcurrentHashMap<Pair<String, String>, Long>()

    @Volatile
    private var lastBusyLog = 0L

    private val queue = GradingQueue<SubmissionWithContext>(
        name = "example_queue",
        capacity = properties.capacity,
        policy = DrainAwarePolicy(
            normal = CpuGatedPolicy(
                min = properties.minConcurrentSubmissions,
                max = properties.maxConcurrentSubmissions,
                cpuThreshold = properties.cpuThreshold,
                cpuUsageProvider = cpuUsageProvider,
            ),
            drainConcurrent = properties.drainConcurrentSubmissions,
            isDraining = ::isDraining,
        ),
        retryPolicy = RetryPolicy.skipClientErrors(properties.maxRetries),
        meterRegistry = meterRegistry,
        handler = ::grade,
        listener = object : GradingQueueListener<SubmissionWithContext> {
            override fun onDispatched(item: SubmissionWithContext) = updateDrainState(item)
            override fun onBlocked(head: SubmissionWithContext) = logBusyThrottled(head)
            override fun onAttemptFailed(item: SubmissionWithContext, attempt: Int, error: Exception, willRetry: Boolean) =
                logFailure(item, attempt, error, willRetry)
            override fun onCompleted(item: SubmissionWithContext, error: Exception?) =
                checkDrainFinished(item.courseSlug, item.exampleSlug)
        },
    )

    @PostConstruct
    fun startQueueProcessor() {
        queue.start()
    }

    @PreDestroy
    fun shutdown() {
        queue.shutdown()
    }

    // ---------------------------------------------------------------------------------------------
    // Producer side
    // ---------------------------------------------------------------------------------------------

    /** Returns false if the queue is full; the caller must then reject the submission. */
    fun addToQueue(courseSlug: String, exampleSlug: String, submission: SubmissionDTO, submissionReceivedAt: LocalDateTime): Boolean {
        val accepted = queue.offer(SubmissionWithContext(courseSlug, exampleSlug, submission, submissionReceivedAt))
        if (accepted) {
            logger.info { "Submission for user ${submission.userId} added to queue for example $exampleSlug." }
        } else {
            logger.error { "Example queue full (capacity ${properties.capacity}) - rejecting submission of user ${submission.userId} for example $exampleSlug." }
        }
        return accepted
    }

    /** Removes the WAITING submissions of an example (e.g. on reset); running ones finish normally. */
    fun removeOutdatedSubmissions(courseSlug: String, exampleSlug: String) {
        val removed = queue.removeWaiting { it.belongsTo(courseSlug, exampleSlug) }
        logger.info { "$removed waiting submission(s) for course \"$courseSlug\" and \"$exampleSlug\" removed." }
    }

    // ---------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------

    fun areInteractiveExampleSubmissionsFullyProcessed(courseSlug: String, exampleSlug: String): Boolean =
        queue.isIdle { it.belongsTo(courseSlug, exampleSlug) }

    /** Blocks until no submission of the example is waiting or running, at most [timeout]. Returns whether it became idle. */
    fun awaitIdle(courseSlug: String, exampleSlug: String, timeout: Duration): Boolean =
        queue.awaitIdle(timeout) { it.belongsTo(courseSlug, exampleSlug) }

    fun isSubmissionInTheQueue(courseSlug: String, exampleSlug: String, userId: String?): Boolean =
        userId != null && getPendingSubmissionFromQueue(courseSlug, exampleSlug, userId) != null

    fun isSubmissionCurrentlyProcessed(courseSlug: String, exampleSlug: String, userId: String?): Boolean =
        userId != null && getRunningSubmission(courseSlug, exampleSlug, userId) != null

    fun getPendingSubmissionFromQueue(courseSlug: String, exampleSlug: String, userId: String): SubmissionDTO? =
        queue.findWaiting { it.belongsTo(courseSlug, exampleSlug, userId) }?.submissionDTO

    fun getRunningSubmission(courseSlug: String, exampleSlug: String, userId: String): SubmissionDTO? =
        queue.findRunning { it.belongsTo(courseSlug, exampleSlug, userId) }?.submissionDTO

    // ---------------------------------------------------------------------------------------------
    // Grading
    // ---------------------------------------------------------------------------------------------

    private fun grade(item: SubmissionWithContext) {
        val submission = exampleSubmissionService.processSubmission(
            item.courseSlug,
            item.exampleSlug,
            item.submissionDTO,
            item.submissionReceivedAt
        )
        forwardToEmbedding(submission)
    }

    // Separate from grading: the graded submission is already committed, so a failure here must not trigger a
    // retry (which would run into the submission lock). Hence everything is caught.
    private fun forwardToEmbedding(newSubmission: Submission) {
        try {
            val example = newSubmission.evaluation?.task
            val courseSlug = example?.course?.slug
            val exampleSlug = example?.slug
            val submissionId = newSubmission.id
            if (courseSlug == null || exampleSlug == null || submissionId == null) {
                logger.error { "Cannot enqueue embedding for submission $submissionId: course, example or id missing." }
                return
            }
            val concatenatedSubmissionContent = newSubmission.files
                .filter { submissionFile -> submissionFile.taskFile?.editable == true }
                .joinToString(separator = "\n") { submissionFile -> submissionFile.content ?: "" }
            embeddingQueueService.addToQueue(courseSlug, exampleSlug, submissionId, concatenatedSubmissionContent)
        } catch (e: Exception) {
            logger.error(e) { "Failed to enqueue embedding for submission ${newSubmission.id}. Grading is saved." }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Drain mode and logging
    // ---------------------------------------------------------------------------------------------

    private fun isDraining(item: SubmissionWithContext): Boolean {
        val key = Pair(item.courseSlug, item.exampleSlug)
        val now = clock.millis()
        val cached = interactiveCache[key]
        val interactive = if (cached != null && now - cached.second < interactiveCacheMillis) {
            cached.first
        } else {
            exampleService.isExampleInteractive(item.courseSlug, item.exampleSlug)
                .also { interactiveCache[key] = Pair(it, now) }
        }
        return !interactive
    }

    private fun updateDrainState(item: SubmissionWithContext) {
        val key = Pair(item.courseSlug, item.exampleSlug)
        if (isDraining(item) && drainStartedAt.putIfAbsent(key, clock.millis()) == null) {
            // +1: the item that was just dispatched has already left the queue.
            val left = queue.countWaiting { it.belongsTo(item.courseSlug, item.exampleSlug) } + 1
            logger.info { "Drain started for example ${item.exampleSlug} in course ${item.courseSlug}: $left submission(s) left." }
        }
    }

    private fun checkDrainFinished(courseSlug: String, exampleSlug: String) {
        val key = Pair(courseSlug, exampleSlug)
        val start = drainStartedAt[key] ?: return
        if (areInteractiveExampleSubmissionsFullyProcessed(courseSlug, exampleSlug) && drainStartedAt.remove(key) != null) {
            logger.info { "Drain finished for example $exampleSlug in course $courseSlug after ${(clock.millis() - start) / 1000}s." }
        }
    }

    private fun logBusyThrottled(head: SubmissionWithContext) {
        val now = clock.millis()
        if (now - lastBusyLog < busyLogIntervalMillis) return
        lastBusyLog = now
        val headWaitSeconds = Duration.between(head.submissionReceivedAt, LocalDateTime.now(clock)).seconds
        logger.info { "Queue waiting: ${queue.waitingCount} queued, ${queue.runningCount} running, head waits ${headWaitSeconds}s." }
    }

    private fun logFailure(item: SubmissionWithContext, attempt: Int, error: Exception, willRetry: Boolean) {
        val who = "${item.submissionDTO.userId} for ${item.exampleSlug}"
        when {
            willRetry ->
                logger.warn(error) { "Error processing submission of $who (attempt $attempt). Retrying." }
            error is ResponseStatusException && error.statusCode.is4xxClientError ->
                logger.warn { "Submission of $who rejected: ${error.reason}. Not retried." }
            else ->
                logger.error(error) { "Submission of $who failed after $attempt attempt(s). Discarding." }
        }
    }

    private fun SubmissionWithContext.belongsTo(courseSlug: String, exampleSlug: String, userId: String? = null): Boolean =
        this.courseSlug == courseSlug && this.exampleSlug == exampleSlug &&
            (userId == null || submissionDTO.userId == userId)
}
