package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.EmbeddingRequestBodyDTO
import ch.uzh.ifi.access.model.dto.EmbeddingResponseBodyDTO
import ch.uzh.ifi.access.repository.SubmissionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.BlockingQueue

@Service
class EmbeddingQueueService(
    private val submissionRepository: SubmissionRepository,
    private val webClient: WebClient,
    @Value("\${llm.service.batch-size}") private val batchSize: Int,
    @Value("\${llm.service.url}") private val llmServiceUrl: String
) {
    private val logger = KotlinLogging.logger {}
    private val llmSubmissionQueue: BlockingQueue<EmbeddingRequestBodyDTO> = LinkedBlockingQueue()
    private lateinit var processorThread: Thread

    fun addToQueue(submissionId: Long, codeSnippet: String) {
        llmSubmissionQueue.offer(EmbeddingRequestBodyDTO(submissionId, codeSnippet))
    }

    @PostConstruct
    fun startBatchProcessor() {
        processorThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (llmSubmissionQueue.size < batchSize) { // TODO optimize! Ideally no busy waiting.
                        Thread.sleep(100)
                        continue
                    }
                    processBatch()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn { "LLM processor thread interrupted. Shutting down." }
                } catch (e: Exception) {
                    logger.error(e) { "Unexpected error in LLM processor thread." }
                }
            }
        }
        processorThread.name = "llm-batch-processor-thread"
        processorThread.isDaemon = true
        processorThread.start()
    }

    private fun processBatch() {
        val batch = mutableListOf<EmbeddingRequestBodyDTO>()
        llmSubmissionQueue.drainTo(batch, batchSize)

        if (batch.isEmpty()) {
            return
        }

        webClient.post()
            .uri("$llmServiceUrl/get_embeddings/")
            .bodyValue(batch)
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
                    logger.info { "Successfully processed and saved embeddings for a batch of ${batch.size} submissions." }
                },
                { error ->
                    logger.error(error) { "Error processing LLM batch. Re-adding to queue." }
                    batch.forEach { llmSubmissionQueue.offer(it) }
                }
            )
    }

    @PreDestroy
    fun shutdown() {
        processorThread.interrupt()
    }
}
