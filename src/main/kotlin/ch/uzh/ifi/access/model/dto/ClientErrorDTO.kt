package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class ClientErrorDTO(
    var message: String = "",
    var stack: String = "",
    var url: String = "",
    var userAgent: String = ""
)
