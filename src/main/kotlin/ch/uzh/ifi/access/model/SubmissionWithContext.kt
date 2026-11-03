package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.dto.SubmissionDTO
import java.time.LocalDateTime

/** A GRADE submission waiting in or running through the example queue. Retries are tracked by the queue itself. */
data class SubmissionWithContext(
    val courseSlug: String,
    val exampleSlug: String,
    val submissionDTO: SubmissionDTO,
    val submissionReceivedAt: LocalDateTime,
)
