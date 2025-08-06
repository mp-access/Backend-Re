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
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
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
    private val submissionQueue: BlockingQueue<SubmissionWithContext> = LinkedBlockingQueue()
    private val executor: ExecutorService = Executors.newFixedThreadPool(maxConcurrentSubmissions)
    private val runningSubmissions = AtomicInteger(0)

    fun addToQueue(courseSlug: String, exampleSlug: String, submission: SubmissionDTO, submissionReceivedAt: LocalDateTime) {
        val submissionWithContext = SubmissionWithContext(courseSlug, exampleSlug, submission, submissionReceivedAt, 0)
        val added = submissionQueue.offer(submissionWithContext)
        if (added) {
            logger.info { "Submission for user ${submission.userId} added to queue for example $exampleSlug." }
        } else {
            logger.error { "Failed to add submission to queue. Queue may be full." }
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

                    if (currentCpuUsage >= cpuThreshold || runningSubmissions.get() >= maxConcurrentSubmissions) {
                        logger.info { "System busy (CPU: %.2f%%) or at max tasks (${runningSubmissions.get()}). Waiting to process submissions...".format(currentCpuUsage * 100) }
                        Thread.sleep(100)
                        continue
                    }

                    val submissionWithContext = submissionQueue.poll()
                    if (submissionWithContext != null) {
                        logger.info { "Processing submission for ${submissionWithContext.submissionDTO.userId}." }
                        runningSubmissions.incrementAndGet()
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
                            } finally {
                                runningSubmissions.decrementAndGet()
                                logger.info { "Submission for ${submissionWithContext.submissionDTO.userId} finished. Running tasks: ${runningSubmissions.get()}" }
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

    fun hasWaitingSubmissions(courseSlug: String, exampleSlug: String): Boolean {
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
        if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
            logger.warn { "Executor did not terminate in time. Forcing shutdown." }
            executor.shutdownNow()
        }
    }
}
