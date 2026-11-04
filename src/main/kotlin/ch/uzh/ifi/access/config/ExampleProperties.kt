package ch.uzh.ifi.access.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * General settings of examples (`examples.*` in application.yml); the queue has its own [ExampleQueueProperties].
 * Invalid values fail the application start with a clear message.
 */
@ConfigurationProperties(prefix = "examples")
data class ExampleProperties(
    /** Seconds after the end of an example during which submissions still belong to the interactive period. */
    val gracePeriod: Long,
    /** Hours a student has to wait after a graded example submission before submitting again. */
    val submissionLockHours: Long = 2,
) {
    init {
        require(gracePeriod >= 0) { "examples.grace-period must not be negative, was $gracePeriod" }
        require(submissionLockHours >= 0) { "examples.submission-lock-hours must not be negative, was $submissionLockHours" }
    }
}
