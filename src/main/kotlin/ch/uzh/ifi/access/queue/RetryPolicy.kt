package ch.uzh.ifi.access.queue

import org.springframework.web.server.ResponseStatusException

/**
 * How often a failed item of a [GradingQueue] is tried again. A retried item is put back at the
 * FRONT of the queue, so it does not lose its place to items that arrived later.
 *
 * @param maxRetries additional attempts after the first one (0 = no retry)
 * @param retryable decides per error whether another attempt can help at all
 */
class RetryPolicy(
    val maxRetries: Int,
    private val retryable: (Exception) -> Boolean = { true },
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative, was $maxRetries" }
    }

    fun isRetryable(error: Exception): Boolean = retryable(error)

    companion object {
        val NONE = RetryPolicy(0)

        /**
         * Retries everything except 4xx errors: those mean "rejected" (too late, submission lock,
         * no attempts left, unknown task, ...) and another attempt would not change the outcome.
         */
        fun skipClientErrors(maxRetries: Int) = RetryPolicy(maxRetries) { error ->
            !(error is ResponseStatusException && error.statusCode.is4xxClientError)
        }
    }
}
