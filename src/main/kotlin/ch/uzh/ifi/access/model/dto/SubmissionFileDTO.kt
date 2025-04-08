package ch.uzh.ifi.access.model.dto

import lombok.Data
import lombok.NoArgsConstructor

@Data
@NoArgsConstructor
class SubmissionFileDTO(
    var problemFileId: Long? = null,
    var content: String? = null
)
