package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.SubmissionWithContext
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.*

/**
 * Queue for GRADE submissions made during the interactive period of an example.
 *
 * Dispatch rules (see canDispatch):
 *  - During the interactive period: always at least [minConcurrent] submissions run, regardless of CPU load
 *    (progress guarantee). Above that, up to [maxConcurrent] run while CPU usage is below [cpuThreshold].
 *  - Drain mode (the example of the queue head is no longer interactive, i.e. end + grace period has passed or the
 *    example was terminated): the CPU gate is ignored and up to [drainConcurrent] submissions run until the queue
 *    is empty. No new entries arrive after the end, so the queue only shrinks.
 */
@Service
class ExampleQueueService(
    private val exampleService: ExampleService,
    private val embeddingQueueService: EmbeddingQueueService,
    private val meterRegistry: MeterRegistry,
    @Value("\${examples.example-queue.cpu-threshold}") private val cpuThreshold: Double,
    @Value("\${examples.example-queue.max-concurrent-submissions}") private val maxConcurrent: Int,
    @Value("\${examples.example-queue.min-concurrent-submissions}") private val minConcurrent: Int,
    @Value("\${examples.example-queue.drain-concurrent-submissions}") private val drainConcurrent: Int
) {
    private val logger = KotlinLogging.logger {}
    private val maxRetries = 2
    private val interactiveCacheMillis = 2_000L
    private val busyLogIntervalMillis = 10_000L

    // Deque so that retries can be put back at the front.
    private val submissionQueue: BlockingDeque<SubmissionWithContext> = LinkedBlockingDeque(1000)
    private val executor: ExecutorService = Executors.newFixedThreadPool(maxOf(maxConcurrent, drainConcurrent))
    private lateinit var processorThread: Thread

    // Submissions currently being processed, keyed by the queue entry id.
    private val running = ConcurrentHashMap<UUID, SubmissionWithContext>()

    // (course, example) -> (isInteractive, checkedAtMillis); avoids a DB lookup on every loop iteration.
    private val interactiveCache = ConcurrentHashMap<Pair<String, String>, Pair<Boolean, Long>>()

    // (course, example) -> drain start in millis, for logging how long draining took.
    private val drainStartedAt = ConcurrentHashMap<Pair<String, String>, Long>()

    @Volatile
    private var lastBusyLog = 0L

    fun addToQueue(courseSlug: String, exampleSlug: String, submission: SubmissionDTO, submissionReceivedAt: LocalDateTime) {
        val submissionWithContext = SubmissionWithContext(courseSlug, exampleSlug, submission, submissionReceivedAt, 0)
        val added = submissionQueue.offer(submissionWithContext)
        if (added) {
            logger.info { "Submission for user ${submission.userId} added to queue for example $exampleSlug." }
        } else {
            logger.error { "Failed to add submission for user ${submission.userId} to queue (queue full)." }
        }
    }

    fun removeOutdatedSubmissions(courseSlug: String, exampleSlug: String) {
        submissionQueue.removeIf { it.courseSlug == courseSlug && it.exampleSlug == exampleSlug }
        logger.info { "All submissions for course \"$courseSlug\" and \"$exampleSlug\" are removed." }
    }

    @PostConstruct
    fun startQueueProcessor() {
        registerGauges()
        processorThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    // Look at the head without removing it, so a waiting submission stays visible as pending.
                    val next = submissionQueue.peek()
                    if (next == null) {
                        Thread.sleep(50)
                        continue
                    }

                    if (!canDispatch(next)) {
                        logBusyThrottled(next)
                        Thread.sleep(100)
                        continue
                    }

                    // Mark as running before removing it from the queue, so it is never invisible to the UI.
                    running[next.id] = next
                    if (!submissionQueue.remove(next)) {
                        // Removed in the meantime (e.g. example reset).
                        running.remove(next.id)
                        continue
                    }
                    updateDrainState(next)
                    dispatch(next)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn { "Processor thread interrupted. Shutting down." }
                } catch (e: Exception) {
                    logger.error(e) { "Unexpected error in processor thread." }
                }
            }
        }
        processorThread.name = "submission-processor-thread"
        processorThread.isDaemon = true
        processorThread.start()
    }

    private fun canDispatch(next: SubmissionWithContext): Boolean {
        val runningCount = running.size
        if (isDraining(next)) {
            return runningCount < drainConcurrent
        }
        if (runningCount < minConcurrent) {
            return true // progress guarantee, independent of CPU load
        }
        if (runningCount >= maxConcurrent) {
            return false
        }
        val currentCpuUsage = meterRegistry.find("system.cpu.usage").gauge()?.value() ?: return false
        return currentCpuUsage < cpuThreshold
    }

    private fun isDraining(item: SubmissionWithContext): Boolean {
        val key = Pair(item.courseSlug, item.exampleSlug)
        val now = System.currentTimeMillis()
        val cached = interactiveCache[key]
        val interactive = if (cached != null && now - cached.second < interactiveCacheMillis) {
            cached.first
        } else {
            exampleService.isExampleInteractive(item.courseSlug, item.exampleSlug)
                .also { interactiveCache[key] = Pair(it, now) }
        }
        return !interactive
    }

    private fun dispatch(item: SubmissionWithContext) {
        executor.submit {
            var result: Submission? = null
            var retry = false
            try {
                result = exampleService.processSubmission(
                    item.courseSlug,
                    item.exampleSlug,
                    item.submissionDTO,
                    item.submissionReceivedAt
                )
            } catch (e: ResponseStatusException) {
                if (e.statusCode.is4xxClientError) {
                    // Too late, submission lock, no attempts left, ...: a retry would not change anything.
                    logger.warn { "Submission of ${item.submissionDTO.userId} for ${item.exampleSlug} rejected: ${e.reason}. Not retried." }
                } else {
                    logger.error(e) { "Error processing submission of ${item.submissionDTO.userId} for ${item.exampleSlug}." }
                    retry = true
                }
            } catch (e: Exception) {
                logger.error(e) { "Error processing submission of ${item.submissionDTO.userId} for ${item.exampleSlug}." }
                retry = true
            } finally {
                running.remove(item.id)
            }
            // Only after removal from `running`: the retried entry keeps its id and could otherwise be
            // dispatched again and then be removed from `running` by this (old) attempt.
            if (retry) {
                handleRetry(item)
            }
            result?.let { forwardToEmbedding(it) }
            checkDrainFinished(item.courseSlug, item.exampleSlug)
        }
    }

    // Separate from grading: the graded submission is already committed, so a failure here must not trigger a
    // retry (which would run into the submission lock).
    private fun forwardToEmbedding(newSubmission: Submission) {
        try {
            val concatenatedSubmissionContent = newSubmission.files
                .filter { submissionFile -> submissionFile.taskFile?.editable == true }
                .joinToString(separator = "\n") { submissionFile -> submissionFile.content ?: "" }
            val example = newSubmission.evaluation!!.task!!
            embeddingQueueService.addToQueue(
                example.course!!.slug!!,
                example.slug!!,
                newSubmission.id!!,
                concatenatedSubmissionContent
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to enqueue embedding for submission ${newSubmission.id}. Grading is saved." }
        }
    }

    private fun handleRetry(submission: SubmissionWithContext) {
        submission.retryCount += 1
        if (submission.retryCount <= maxRetries) {
            if (submissionQueue.offerFirst(submission)) {
                logger.warn { "Retrying submission for ${submission.submissionDTO.userId} (attempt ${submission.retryCount})" }
            } else {
                logger.error { "Could not re-queue submission for ${submission.submissionDTO.userId} (queue full). Discarding." }
            }
        } else {
            logger.error {
                "Submission for ${submission.submissionDTO.userId} failed after $maxRetries retries. Discarding."
            }
        }
    }

    private fun updateDrainState(item: SubmissionWithContext) {
        val key = Pair(item.courseSlug, item.exampleSlug)
        if (isDraining(item) && drainStartedAt.putIfAbsent(key, System.currentTimeMillis()) == null) {
            val left = submissionQueue.count { it.courseSlug == item.courseSlug && it.exampleSlug == item.exampleSlug } + 1
            logger.info { "Drain started for example ${item.exampleSlug} in course ${item.courseSlug}: $left submission(s) left." }
        }
    }

    private fun checkDrainFinished(courseSlug: String, exampleSlug: String) {
        val key = Pair(courseSlug, exampleSlug)
        val start = drainStartedAt[key] ?: return
        if (areInteractiveExampleSubmissionsFullyProcessed(courseSlug, exampleSlug) && drainStartedAt.remove(key) != null) {
            logger.info { "Drain finished for example $exampleSlug in course $courseSlug after ${(System.currentTimeMillis() - start) / 1000}s." }
        }
    }

    private fun logBusyThrottled(next: SubmissionWithContext) {
        val now = System.currentTimeMillis()
        if (now - lastBusyLog < busyLogIntervalMillis) return
        lastBusyLog = now
        val headWaitSeconds = Duration.between(next.submissionReceivedAt, LocalDateTime.now()).seconds
        logger.info { "Queue waiting: ${submissionQueue.size} queued, ${running.size} running, head waits ${headWaitSeconds}s." }
    }

    private fun registerGauges() {
        meterRegistry.gauge("example_queue.waiting", submissionQueue) { it.size.toDouble() }
        meterRegistry.gauge("example_queue.running", running) { it.size.toDouble() }
    }

    private fun runningFor(courseSlug: String, exampleSlug: String): List<SubmissionWithContext> {
        return running.values.filter { it.courseSlug == courseSlug && it.exampleSlug == exampleSlug }
    }

    fun areInteractiveExampleSubmissionsFullyProcessed(courseSlug: String, exampleSlug: String): Boolean {
        return !hasWaitingSubmissions(courseSlug, exampleSlug) && runningFor(courseSlug, exampleSlug).isEmpty()
    }

    private fun hasWaitingSubmissions(courseSlug: String, exampleSlug: String): Boolean {
        return submissionQueue.any {
            it.courseSlug == courseSlug && it.exampleSlug == exampleSlug
        }
    }

    fun isSubmissionInTheQueue(courseSlug: String, exampleSlug: String, userId: String?): Boolean {
        if (userId == null) return false
        return submissionQueue.any {
            it.submissionDTO.userId == userId && it.exampleSlug == exampleSlug && it.courseSlug == courseSlug
        }
    }

    fun isSubmissionCurrentlyProcessed(courseSlug: String, exampleSlug: String, userId: String?): Boolean {
        if (userId == null) return false
        return runningFor(courseSlug, exampleSlug).any { it.submissionDTO.userId == userId }
    }

    fun getPendingSubmissionFromQueue(courseSlug: String, exampleSlug: String, userId: String): SubmissionDTO? {
        val submissionWithContext = submissionQueue.find {
            it.courseSlug == courseSlug && it.exampleSlug == exampleSlug && it.submissionDTO.userId == userId
        }
        return submissionWithContext?.submissionDTO
    }

    fun getRunningSubmission(courseSlug: String, exampleSlug: String, userId: String): SubmissionDTO? {
        return runningFor(courseSlug, exampleSlug).firstOrNull { it.submissionDTO.userId == userId }?.submissionDTO
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down executor and processor thread." }
        processorThread.interrupt()
        executor.shutdown()
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            logger.warn { "Executor didn't shut down gracefully. Forcing shutdown." }
            executor.shutdownNow()
        }
    }
}
