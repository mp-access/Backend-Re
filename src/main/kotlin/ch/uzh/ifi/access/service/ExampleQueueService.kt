package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.SubmissionWithContext
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.*

@Service
class ExampleQueueService (
    private val exampleService: ExampleService,
    private val embeddingQueueService: EmbeddingQueueService,
    private val meterRegistry: MeterRegistry,
    @Value("\${examples.example-queue.cpu-threshold}") private val cpuThreshold: Double,
    @Value("\${examples.example-queue.max-concurrent-submissions}") private val maxConcurrentSubmissions: Int
) {
    private val logger = KotlinLogging.logger {}
    private val maxRetries = 2
    private val submissionQueue: BlockingQueue<SubmissionWithContext> = LinkedBlockingQueue(1000)
    private val executor: ExecutorService = Executors.newFixedThreadPool(maxConcurrentSubmissions)
    private lateinit var processorThread: Thread
    private val runningSubmissionsPerExample = ConcurrentHashMap<Pair<String, String>, MutableList<SubmissionDTO>>()

    fun addToQueue(courseSlug: String, exampleSlug: String, submission: SubmissionDTO, submissionReceivedAt: LocalDateTime) {
        val submissionWithContext = SubmissionWithContext(courseSlug, exampleSlug, submission, submissionReceivedAt, 0)
        val added = submissionQueue.offer(submissionWithContext)
        if (added) {
            logger.info { "Submission for user ${submission.userId} added to queue for example $exampleSlug." }
        } else {
            logger.error { "Failed to add submission to queue." }
        }
    }

    fun removeOutdatedSubmissions(courseSlug: String, exampleSlug: String) {
        submissionQueue.removeIf { it.courseSlug == courseSlug && it.exampleSlug == exampleSlug }
        logger.info { "All submissions for course \"$courseSlug\" and \"$exampleSlug\" are removed." }
    }

    @PostConstruct
    fun startQueueProcessor() {
        processorThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val currentCpuUsage: Double? = meterRegistry.find("system.cpu.usage")
                        .gauge()
                        ?.value()

                    if (currentCpuUsage == null) {
                        logger.warn { "Micrometer metric 'system.cpu.usage' is not yet available. Waiting..." }
                        Thread.sleep(1000)
                        continue
                    }

                    if (currentCpuUsage >= cpuThreshold || getTotalRunningSubmissions() >= maxConcurrentSubmissions) {
                        logger.info { "System busy (CPU: %.2f%%) or at max tasks (${getTotalRunningSubmissions()}). Waiting to process submissions...".format(currentCpuUsage * 100) }
                        Thread.sleep(100)
                        continue
                    }

                    val submissionWithContext = submissionQueue.take()
                    val exampleKey = Pair(submissionWithContext.courseSlug, submissionWithContext.exampleSlug)
                    val userId = submissionWithContext.submissionDTO.userId!!
                    runningSubmissionsPerExample
                        .computeIfAbsent(exampleKey) { Collections.synchronizedList(mutableListOf()) }
                        .add(submissionWithContext.submissionDTO)
                    executor.submit {
                        try {
                            val newSubmission = exampleService.processSubmission(
                                submissionWithContext.courseSlug,
                                submissionWithContext.exampleSlug,
                                submissionWithContext.submissionDTO,
                                submissionWithContext.submissionReceivedAt
                            )

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
                            logger.error(e) {
                                "Error processing submission for ${submissionWithContext.submissionDTO.userId}"
                            }
                            handleRetry(submissionWithContext)
                        } finally {
                            val runningSubmissionsList = runningSubmissionsPerExample[exampleKey]
                            runningSubmissionsList?.let { list ->
                                synchronized(list) {
                                    list.removeIf {
                                        submissionDTO -> submissionDTO.userId == userId
                                    }
                                    if (list.isEmpty()) {
                                        runningSubmissionsPerExample.remove(exampleKey, list)
                                    }
                                }
                            }
                        }
                    }
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

    private fun handleRetry(submission: SubmissionWithContext) {
        submission.retryCount += 1
        if (submission.retryCount <= maxRetries) {
            submissionQueue.offer(submission)
            logger.warn {
                "Retrying submission for ${submission.submissionDTO.userId} (attempt ${submission.retryCount})"
            }
        } else {
            logger.error {
                "Submission for ${submission.submissionDTO.userId} failed after $maxRetries retries. Discarding."
            }
        }
    }

    fun areInteractiveExampleSubmissionsFullyProcessed(courseSlug: String, exampleSlug: String): Boolean {
        return !hasWaitingSubmissions(courseSlug, exampleSlug) &&
                !runningSubmissionsPerExample.containsKey(Pair(courseSlug, exampleSlug))
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

    private fun getTotalRunningSubmissions(): Int {
        return runningSubmissionsPerExample.values.sumOf {
            synchronized(it) {
                it.size
            }
        }
    }

    fun isSubmissionCurrentlyProcessed(courseSlug: String, exampleSlug: String, userId: String?): Boolean {
        if (userId == null) {
            return false
        }
        val exampleKey = Pair(courseSlug, exampleSlug)
        val runningSubmissionsList = runningSubmissionsPerExample[exampleKey]
        runningSubmissionsList?.let { list ->
            synchronized(list) {
                val submissionFound = list.any {
                        submissionDTO -> submissionDTO.userId == userId
                }
                return submissionFound
            }
        }
        return false
    }

    fun getPendingSubmissionFromQueue(courseSlug: String, exampleSlug: String, userId: String): SubmissionDTO? {
        val submissionWithContext = submissionQueue.find {
            it.courseSlug == courseSlug && it.exampleSlug == exampleSlug && it.submissionDTO.userId == userId
        }
        return submissionWithContext?.submissionDTO
    }

    fun getRunningSubmission(courseSlug: String, exampleSlug: String, userId: String): SubmissionDTO? {
        val exampleKey = Pair(courseSlug, exampleSlug)
        val runningSubmissionsList = runningSubmissionsPerExample[exampleKey]
        val submissionDTO = runningSubmissionsList?.let { list ->
            synchronized(list) {
                list.find {
                        submissionDTO -> submissionDTO.userId == userId
                }
            }
        }
        return submissionDTO
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
