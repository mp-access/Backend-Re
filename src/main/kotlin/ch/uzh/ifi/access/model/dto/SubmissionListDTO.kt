package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class SubmissionListDTO(
    var submissionIds: List<Long> = emptyList()
)
