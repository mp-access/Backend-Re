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
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@Service
class ExampleQueueService (
    private val exampleService: ExampleService,
    private val meterRegistry: MeterRegistry,
    @Value("\${examples.example-queue.cpu-threshold}") private val cpuThreshold: Double,
    @Value("\${examples.example-queue.max-concurrent-submissions}") private val maxConcurrentSubmissions: Int
) {
    private val logger = KotlinLogging.logger {}
    private val maxRetries = 2
    private val submissionQueue: BlockingQueue<SubmissionWithContext> = LinkedBlockingQueue(1000)
    private val executor: ExecutorService = Executors.newFixedThreadPool(maxConcurrentSubmissions)
    private lateinit var processorThread: Thread
    private val runningSubmissionsPerExample = ConcurrentHashMap<Pair<String, String>, AtomicInteger>()

    fun addToQueue(courseSlug: String, exampleSlug: String, submission: SubmissionDTO, submissionReceivedAt: LocalDateTime) {
        val submissionWithContext = SubmissionWithContext(courseSlug, exampleSlug, submission, submissionReceivedAt, 0)
        val added = submissionQueue.offer(submissionWithContext)
        if (added) {
            logger.info { "Submission for user ${submission.userId} added to queue for example $exampleSlug." }
        } else {
            logger.error { "Failed to add submission to queue." }
        }
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
                    runningSubmissionsPerExample.computeIfAbsent(exampleKey) { AtomicInteger(0) }.incrementAndGet()
                    executor.submit {
                        try {
                            exampleService.processSubmission(
                                submissionWithContext.courseSlug,
                                submissionWithContext.exampleSlug,
                                submissionWithContext.submissionDTO,
                                submissionWithContext.submissionReceivedAt
                            )
                        } catch (e: Exception) {
                            logger.error(e) {
                                "Error processing submission for ${submissionWithContext.submissionDTO.userId}"
                            }
                            handleRetry(submissionWithContext)
                        } finally {
                            val count = runningSubmissionsPerExample[exampleKey]?.decrementAndGet()
                            if (count == 0) {
                                runningSubmissionsPerExample.remove(exampleKey)
                            }
                            logger.debug {
                                "Completed submission for ${submissionWithContext.submissionDTO.userId}"
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

    private fun getTotalRunningSubmissions(): Int {
        return runningSubmissionsPerExample.values.sumOf { it.get() }
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
