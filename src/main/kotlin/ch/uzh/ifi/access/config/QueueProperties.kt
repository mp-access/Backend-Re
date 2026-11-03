package ch.uzh.ifi.access.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Settings of the example queue (`examples.example-queue.*` in application.yml).
 * Invalid combinations fail the application start with a clear message.
 */
@ConfigurationProperties(prefix = "examples.example-queue")
data class ExampleQueueProperties(
    /** Soft brake during the interactive period: above min, more run only while CPU usage is below this. */
    val cpuThreshold: Double,
    /** Upper bound during the interactive period. Keep below docker.pool.size. */
    val maxConcurrentSubmissions: Int,
    /** Always running during the interactive period, regardless of CPU load (progress guarantee). */
    val minConcurrentSubmissions: Int,
    /** Parallelism after the example ended (drain mode, CPU load ignored) until the queue is empty. */
    val drainConcurrentSubmissions: Int,
    /** Max submissions buffered; when full, further submissions are rejected with HTTP 503. */
    val capacity: Int,
    /** Retries of a failed grading (not for 4xx rejections such as the submission lock). */
    val maxRetries: Int,
) {
    init {
        require(cpuThreshold in 0.0..1.0) { "examples.example-queue.cpu-threshold must be between 0 and 1, was $cpuThreshold" }
        require(maxConcurrentSubmissions >= 1) { "examples.example-queue.max-concurrent-submissions must be at least 1, was $maxConcurrentSubmissions" }
        require(minConcurrentSubmissions in 0..maxConcurrentSubmissions) {
            "examples.example-queue.min-concurrent-submissions ($minConcurrentSubmissions) must be between 0 and max-concurrent-submissions ($maxConcurrentSubmissions)"
        }
        require(drainConcurrentSubmissions >= 1) { "examples.example-queue.drain-concurrent-submissions must be at least 1, was $drainConcurrentSubmissions" }
        require(capacity >= 1) { "examples.example-queue.capacity must be at least 1, was $capacity" }
        require(maxRetries >= 0) { "examples.example-queue.max-retries must not be negative, was $maxRetries" }
    }
}

/**
 * Settings of the submission queue for regular tasks (`submission.queue.*` in application.yml).
 * Invalid values fail the application start with a clear message.
 */
@ConfigurationProperties(prefix = "submission.queue")
data class SubmissionQueueProperties(
    /** Max submissions buffered before backpressure kicks in (HTTP 503). */
    val capacity: Int,
    /** Gradings running in parallel. Keep roughly equal to docker.pool.size. */
    val workers: Int,
    /** Retries of a failed grading (not for 4xx rejections such as "no attempts left"). */
    val maxRetries: Int,
    /** How long the async request waits for a grade before returning 503. */
    val requestTimeoutSeconds: Long,
) {
    init {
        require(capacity >= 1) { "submission.queue.capacity must be at least 1, was $capacity" }
        require(workers >= 1) { "submission.queue.workers must be at least 1, was $workers" }
        require(maxRetries >= 0) { "submission.queue.max-retries must not be negative, was $maxRetries" }
        require(requestTimeoutSeconds >= 1) { "submission.queue.requestTimeoutSeconds must be at least 1, was $requestTimeoutSeconds" }
    }
}

@Configuration
@EnableConfigurationProperties(ExampleQueueProperties::class, SubmissionQueueProperties::class)
class QueuePropertiesConfig
