package ch.uzh.ifi.access.model.dto

import lombok.Data
import lombok.NoArgsConstructor

@Data
class AssignmentInformationDTO(
    var language: String? = null,
    var title: String? = null
)
