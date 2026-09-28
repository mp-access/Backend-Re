package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.dto.SubmissionDTO
import lombok.Data
import java.time.LocalDateTime
import java.util.UUID

@Data
data class SubmissionWithContext(
    val courseSlug: String,
    val exampleSlug: String,
    val submissionDTO: SubmissionDTO,
    val submissionReceivedAt: LocalDateTime,
    var retryCount: Int,
    // Identifies one queue entry across retries; used to track it while it is running.
    val id: UUID = UUID.randomUUID()
)
