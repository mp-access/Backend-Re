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
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Service
class EmbeddingQueueService(
    private val submissionRepository: SubmissionRepository,
    private val exampleService: ExampleService,
    private val webClient: WebClient,
    @Value("\${llm.service.batch-size}") private val batchSize: Int,
    @Value("\${llm.service.url}") private val llmServiceUrl: String
) {
    companion object {
        private const val MAX_RETRIES = 2
        private const val BATCH_TIMEOUT_MILLIS = 2000L
    }

    private val logger = KotlinLogging.logger {}
    private val embeddingQueue: BlockingQueue<EmbeddingWithContext> = LinkedBlockingQueue()
    private val runningSubmissions = ConcurrentHashMap<Pair<String, String>, MutableSet<Long>>()
    private val processorThread = Thread(::runProcessor, "batch-embedding-processor-thread")

    fun addToQueue(courseSlug: String, exampleSlug: String, submissionId: Long, codeSnippet: String) {
        embeddingQueue.offer(
            EmbeddingWithContext(
                courseSlug = courseSlug,
                exampleSlug = exampleSlug,
                embeddingRequestBody = EmbeddingRequestBodyDTO(submissionId, codeSnippet),
                retryCount = 0,
                forceComputation = false,
            )
        )
    }

    // Re-added submissions start at MAX_RETRIES, so they get no further retry after a failure.
    fun reAddSubmissionsToQueue(courseSlug: String, exampleSlug: String, submissionIds: List<Long>) {
        val submissionsById = submissionRepository.findAllById(submissionIds).filterNotNull().associateBy { it.id }
        submissionIds.forEach { submissionId ->
            val submission = submissionsById[submissionId] ?: return@forEach
            val submissionContent = submission.files
                .filter { submissionFile -> submissionFile.taskFile?.editable == true }
                .joinToString(separator = "\n") { submissionFile -> submissionFile.content ?: "" }
            embeddingQueue.offer(
                EmbeddingWithContext(
                    courseSlug = courseSlug,
                    exampleSlug = exampleSlug,
                    embeddingRequestBody = EmbeddingRequestBodyDTO(submissionId, submissionContent),
                    retryCount = MAX_RETRIES,
                    forceComputation = true,
                )
            )
        }
    }

    fun removeOutdatedSubmissions(courseSlug: String, exampleSlug: String) {
        embeddingQueue.removeIf { it.courseSlug == courseSlug && it.exampleSlug == exampleSlug }
        logger.info { "All embedding requests for course \"$courseSlug\" and \"$exampleSlug\" are removed." }
    }

    fun getRunningSubmissions(courseSlug: String, exampleSlug: String): List<Long> =
        runningSubmissions[Pair(courseSlug, exampleSlug)]?.toList() ?: listOf()

    @PostConstruct
    fun startBatchProcessor() {
        processorThread.isDaemon = true
        processorThread.start()
    }

    @PreDestroy
    fun shutdown() {
        processorThread.interrupt()
    }

    private fun runProcessor() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                processBatch(nextBatch())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn { "Embedding processor thread interrupted. Shutting down." }
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error in embedding processor thread." }
            }
        }
    }

    // Collects up to batchSize items, but waits at most BATCH_TIMEOUT_MILLIS after the first one.
    // A forced item ends collection immediately; nothing already taken from the queue is dropped.
    private fun nextBatch(): List<EmbeddingWithContext> {
        val batch = mutableListOf(embeddingQueue.take())
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BATCH_TIMEOUT_MILLIS)
        while (batch.size < batchSize && batch.none { it.forceComputation }) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val next = embeddingQueue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            batch.add(next)
        }
        return batch
    }

    private fun processBatch(submissions: List<EmbeddingWithContext>) {
        submissions.forEach { markRunning(it) }
        try {
            val batchForRequest = submissions.map { it.embeddingRequestBody }
            val responseItems = try {
                webClient.post()
                    .uri("$llmServiceUrl/calculate_embeddings/")
                    .bodyValue(batchForRequest)
                    .retrieve()
                    .bodyToMono(Array<EmbeddingResponseBodyDTO>::class.java)
                    .block() ?: emptyArray()
            } catch (error: Exception) {
                logger.error(error) { "Error processing embedding batch." }
                retry(submissions)
                return
            }

            saveEmbeddings(responseItems)
            logger.info { "Successfully calculated and saved embeddings for a batch of ${batchForRequest.size} submissions." }

            submissions.groupBy { it.exampleKey() }.forEach { (key, items) ->
                checkExampleCompleted(key.first, key.second, items.any { it.forceComputation })
            }
        } finally {
            submissions.forEach { markFinished(it) }
        }
    }

    private fun saveEmbeddings(responseItems: Array<EmbeddingResponseBodyDTO>) {
        val embeddingsById = responseItems.associate { it.submissionId to it.embedding }
        val submissions = submissionRepository.findAllById(embeddingsById.keys).filterNotNull()
        submissions.forEach { submission ->
            submission.embedding = embeddingsById.getValue(submission.id!!).toDoubleArray()
        }
        submissionRepository.saveAll(submissions)
    }

    private fun checkExampleCompleted(courseSlug: String, exampleSlug: String, forced: Boolean) {
        val interactiveSubmissions = exampleService.getInteractiveExampleSubmissions(courseSlug, exampleSlug)
        if (interactiveSubmissions.size != exampleService.getExampleSubmissionCount(courseSlug, exampleSlug)) return

        exampleService.requestExampleInformationUpdate(courseSlug, exampleSlug)
        if (forced) return

        val submissionsWithoutEmbeddings = interactiveSubmissions.filter { it.embedding.isEmpty() }
        if (submissionsWithoutEmbeddings.isNotEmpty()) {
            reAddSubmissionsToQueue(courseSlug, exampleSlug, submissionsWithoutEmbeddings.map { it.id!! })
        }
    }

    private fun retry(submissions: List<EmbeddingWithContext>) {
        submissions.forEach { embeddingWithContext ->
            embeddingWithContext.retryCount += 1
            if (embeddingWithContext.retryCount <= MAX_RETRIES) {
                embeddingQueue.offer(embeddingWithContext)
            } else {
                logger.error { "Max retries ($MAX_RETRIES) reached for submission ${embeddingWithContext.embeddingRequestBody.submissionId}. Its embedding will remain empty for the time being." }
            }
        }
    }

    private fun markRunning(embeddingWithContext: EmbeddingWithContext) {
        runningSubmissions.compute(embeddingWithContext.exampleKey()) { _, running ->
            (running ?: ConcurrentHashMap.newKeySet<Long>()).apply { add(embeddingWithContext.embeddingRequestBody.submissionId) }
        }
    }

    private fun markFinished(embeddingWithContext: EmbeddingWithContext) {
        runningSubmissions.computeIfPresent(embeddingWithContext.exampleKey()) { _, running ->
            running.remove(embeddingWithContext.embeddingRequestBody.submissionId)
            if (running.isEmpty()) null else running
        }
    }

    private fun EmbeddingWithContext.exampleKey() = Pair(courseSlug, exampleSlug)
}
