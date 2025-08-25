package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.EmbeddingWithContext
import ch.uzh.ifi.access.model.dto.EmbeddingRequestBodyDTO
import ch.uzh.ifi.access.model.dto.EmbeddingResponseBodyDTO
import ch.uzh.ifi.access.repository.SubmissionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap

@Service
class EmbeddingQueueService(
    private val submissionRepository: SubmissionRepository,
    private val webClient: WebClient,
    @Value("\${llm.service.batch-size}") private val batchSize: Int,
    @Value("\${llm.service.url}") private val llmServiceUrl: String
) {
    private val logger = KotlinLogging.logger {}
    private val maxRetries = 2
    private val embeddingQueue: BlockingQueue<EmbeddingWithContext> = LinkedBlockingQueue()
    private lateinit var processorThread: Thread
    private val embeddingCurrentlyComputedForSubmissions = ConcurrentHashMap<Pair<String, String>, MutableList<Long>>()

    fun addToQueue(courseSlug: String, exampleSlug: String, submissionId: Long, codeSnippet: String) {
        val embeddingWithContext = EmbeddingWithContext(courseSlug,
                                                        exampleSlug,
                                                        EmbeddingRequestBodyDTO(submissionId, codeSnippet),
                                                        0,
                                                        false)
        embeddingQueue.offer(embeddingWithContext)
    }

    fun reAddSubmissionsToQueue(courseSlug: String, exampleSlug: String, submissionIds: List<Long>) {
        submissionIds.forEach { submissionId ->
            val submission = submissionRepository.findById(submissionId).orElse(null)
            if (submission != null) {
                val submissionContent = submission.files
                    .filter { submissionFile -> submissionFile.taskFile?.editable == true }
                    .joinToString(separator = "\n") { submissionFile -> submissionFile.content ?: "" }
                val embeddingWithContext = EmbeddingWithContext(courseSlug,
                    exampleSlug,
                    EmbeddingRequestBodyDTO(submissionId, submissionContent),
                    maxRetries,
                    true)
                embeddingQueue.offer(embeddingWithContext)
            }
        }
    }

    fun removeOutdatedSubmissions(courseSlug: String, exampleSlug: String) {
        embeddingQueue.removeIf { it.courseSlug == courseSlug && it.exampleSlug == exampleSlug }
        logger.info { "All embedding requests for course \"$courseSlug\" and \"$exampleSlug\" are removed." }
    }

    @PostConstruct
    fun startBatchProcessor() {
        processorThread = Thread {
            try {
                outer@while (!Thread.currentThread().isInterrupted) {
                    val batchWithContext = mutableListOf<EmbeddingWithContext>()

                    val firstItem = embeddingQueue.take()
                    batchWithContext.add(firstItem)

                    while (batchWithContext.size < batchSize) {

                        val forcedEmbeddingsCalculations = batchWithContext.filter { it.forceComputation }
                        if (forcedEmbeddingsCalculations.isNotEmpty()) {
                            processBatch(forcedEmbeddingsCalculations)
                            continue@outer
                        }

                        val nextItem = embeddingQueue.take()
                        batchWithContext.add(nextItem)
                    }

                    processBatch(batchWithContext)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn { "Embedding processor thread interrupted. Shutting down." }
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error in embedding processor thread." }
            }
        }
        processorThread.name = "batch-embedding-processor-thread"
        processorThread.isDaemon = true
        processorThread.start()
    }


    private fun processBatch(submissions: List<EmbeddingWithContext>) {
        submissions.forEach { embeddingWithContext ->
            val exampleKey = Pair(embeddingWithContext.courseSlug, embeddingWithContext.exampleSlug)
            embeddingCurrentlyComputedForSubmissions
                .computeIfAbsent(exampleKey) { Collections.synchronizedList(mutableListOf()) }
                .add(embeddingWithContext.embeddingRequestBody.submissionId)
        }
        val batchForRequest =
            submissions.map { submissionWithContext -> submissionWithContext.embeddingRequestBody }

        webClient.post()
            .uri("$llmServiceUrl/calculate_embeddings/")
            .bodyValue(batchForRequest)
            .retrieve()
            .bodyToMono(Array<EmbeddingResponseBodyDTO>::class.java)
            .subscribe(
                { responseItems ->
                    responseItems.forEach { item ->
                        submissionRepository.findById(item.submissionId).ifPresent { submission ->
                            submission.embedding = item.embedding.toDoubleArray()
                            submissionRepository.save(submission)
                        }
                    }
                    logger.info { "Successfully calculated and saved embeddings for a batch of ${batchForRequest.size} submissions." }
                },
                { error ->
                    logger.error(error) { "Error processing embedding batch." }
                    submissions.forEach { embeddingWithContext ->
                        embeddingWithContext.retryCount += 1
                        if (embeddingWithContext.retryCount <= maxRetries) {
                            embeddingQueue.offer(embeddingWithContext)
                        } else {
                            logger.error { "Max retries ($maxRetries) reached for submission ${embeddingWithContext.embeddingRequestBody.submissionId}. Its embedding will remain empty for the time being." }
                        }
                    }
                }
            )

        submissions.forEach { embeddingWithContext ->
            val exampleKey = Pair(embeddingWithContext.courseSlug, embeddingWithContext.exampleSlug)
            val submissionList = embeddingCurrentlyComputedForSubmissions[exampleKey]
            submissionList?.let { list ->
                synchronized(list) {
                    list.removeIf { submissionId ->
                        submissionId == embeddingWithContext.embeddingRequestBody.submissionId
                    }
                    if (list.isEmpty()) {
                        embeddingCurrentlyComputedForSubmissions.remove(exampleKey, list)
                    }
                }
            }
        }
    }

    fun getRunningSubmissions(courseSlug: String, exampleSlug: String): List<Long> {
        val exampleKey = Pair(courseSlug, exampleSlug)
        val runningSubmissionsList = embeddingCurrentlyComputedForSubmissions[exampleKey]
        return runningSubmissionsList?.toList() ?: listOf()
    }

    @PreDestroy
    fun shutdown() {
        processorThread.interrupt()
    }
}
