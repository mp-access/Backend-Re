package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.dto.SubmissionDTO
import lombok.Data
import java.time.LocalDateTime

@Data
data class SubmissionWithContext(
    val courseSlug: String,
    val exampleSlug: String,
    val submissionDTO: SubmissionDTO,
    val submissionReceivedAt: LocalDateTime,
    var retryCount: Int
)
