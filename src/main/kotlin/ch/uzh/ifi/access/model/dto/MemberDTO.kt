package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class MemberDTO (
    var name: String? = null,
    var email: String? = null
)