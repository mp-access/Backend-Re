package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class ProblemInformationDTO(
    var language: String? = null,
    var title: String? = null,
    var instructionsFile: String? = null
)
