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
import kotlin.concurrent.thread

@Service
class ExampleQueueService (
    private val exampleService: ExampleService,
    private val meterRegistry: MeterRegistry,
    @Value("\${examples.example-queue.cpu-threshold}") private val cpuThreshold: Double,
    @Value("\${examples.example-queue.max-concurrent-submissions}") private val maxConcurrentSubmissions: Int
) {
    private val logger = KotlinLogging.logger {}
    private val coolDownPeriod = 500L
    private val maxRetries = 2
    private val submissionQueue: BlockingQueue<SubmissionWithContext> = LinkedBlockingQueue()
    private val executor: ExecutorService = Executors.newFixedThreadPool(maxConcurrentSubmissions)
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
        thread(name = "submission-processor-thread") {
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

                    val submissionWithContext = submissionQueue.poll()
                    if (submissionWithContext != null) {
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
                                logger.error(e) { "Error processing submission for ${submissionWithContext.submissionDTO.userId}: ${e.message}" }
                                submissionWithContext.retryCount += 1
                                if (submissionWithContext.retryCount <= maxRetries) {
                                    submissionQueue.offer(submissionWithContext)
                                    logger.warn { "Submission re-added to queue (attempt ${submissionWithContext.retryCount})." }
                                } else {
                                    logger.error { "Submission for ${submissionWithContext.submissionDTO.userId} failed after ${maxRetries} retries. Discarding." }
                                }
                            } finally {
                                val count = runningSubmissionsPerExample[exampleKey]?.decrementAndGet()
                                if (count == 0) {
                                    runningSubmissionsPerExample.remove(exampleKey)
                                }
                                logger.info { "Submission task for ${submissionWithContext.submissionDTO.userId} finished. Running tasks: ${getTotalRunningSubmissions()}" }
                            }
                        }
                        Thread.sleep(coolDownPeriod) // cool-down period to prevent "The Thundering Herd"-problem
                    } else {
                        Thread.sleep(1000)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn { "Submission processor thread interrupted. Shutting down." }
                } catch (e: Exception) {
                    logger.error(e) { "An unexpected error occurred in the queue processor loop." }
                }
            }
        }
    }

    private fun getTotalRunningSubmissions(): Int {
        return runningSubmissionsPerExample.values.sumOf { it.get() }
    }

    fun areInteractiveExampleSubmissionsFullyProcessed(courseSlug: String, exampleSlug: String) : Boolean {
        return if (hasWaitingSubmissions(courseSlug, exampleSlug)) {
            false
        } else {
            !runningSubmissionsPerExample.containsKey(Pair(courseSlug, exampleSlug))
        }
    }

    private fun hasWaitingSubmissions(courseSlug: String, exampleSlug: String): Boolean {
        for (submission in submissionQueue) {
            if (submission.exampleSlug == exampleSlug && submission.courseSlug == courseSlug) {
                return true
            }
        }
        return false
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down submission executor and queue processor thread." }
        executor.shutdown()
        Thread.currentThread().interrupt()
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            logger.warn { "Executor did not terminate in time. Forcing shutdown." }
            executor.shutdownNow()
        }
    }
}
