package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class UserDTO(
    var userId: String? = null,
    var problemId: Long? = null,
    var addAttempts: Int? = null,
)
