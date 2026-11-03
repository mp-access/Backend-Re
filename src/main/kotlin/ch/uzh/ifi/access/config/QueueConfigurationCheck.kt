package ch.uzh.ifi.access.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Checks at startup whether the queue limits fit the Docker container pool. Both queues borrow containers from
 * the same pool (as do RUN / TEST submissions, which bypass the queues). A queue allowing more parallel gradings
 * than the pool has containers makes graders wait for a container and, after the borrow timeout, fall back to
 * slow one-shot containers. Only warns: the pool may be disabled, and the fallback keeps the system working.
 */
@Component
class QueueConfigurationCheck(
    private val exampleQueue: ExampleQueueProperties,
    private val submissionQueue: SubmissionQueueProperties,
    @Value("\${docker.pool.enabled:true}") private val poolEnabled: Boolean,
    @Value("\${docker.pool.size:10}") private val poolSize: Int,
) {
    private val logger = KotlinLogging.logger {}

    fun warnings(): List<String> {
        if (!poolEnabled) return emptyList()
        return buildList {
            fun check(setting: String, value: Int) {
                if (value > poolSize) {
                    add("$setting ($value) exceeds docker.pool.size ($poolSize): gradings will wait for a container or fall back to one-shot containers.")
                }
            }
            check("examples.example-queue.max-concurrent-submissions", exampleQueue.maxConcurrentSubmissions)
            check("examples.example-queue.drain-concurrent-submissions", exampleQueue.drainConcurrentSubmissions)
            check("submission.queue.workers", submissionQueue.workers)
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun logWarnings() {
        val warnings = warnings()
        warnings.forEach { logger.warn { "Queue configuration: $it" } }
        if (warnings.isEmpty() && poolEnabled) {
            logger.info { "Queue configuration fits docker.pool.size ($poolSize)." }
        }
    }
}
