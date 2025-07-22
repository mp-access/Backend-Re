package ch.uzh.ifi.access.model.dto

import lombok.Data
import java.time.LocalDateTime

@Data
class SubmissionSseDTO(
    var submissionId: Long = 0,
    var studentId: String? = null,
    var date: LocalDateTime? = null,
    var points: Double = 0.0,
    var testsPassed: List<Int> = emptyList(),
    var content: String? = null,
)
