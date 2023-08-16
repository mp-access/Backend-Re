package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class StudentDTO(
    var firstName: String? = null,
    var lastName: String? = null,
    var email: String? = null,
    var points: Double? = null
)
