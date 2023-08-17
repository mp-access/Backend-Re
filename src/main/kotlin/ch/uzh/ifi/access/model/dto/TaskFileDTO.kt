package ch.uzh.ifi.access.model.dto

import lombok.Data
import lombok.NoArgsConstructor

@Data
@NoArgsConstructor
class TaskFileDTO {
    var path: String? = null
    var template: String? = null
    // refers to the programming language, e.g. "py"
    var language: String? = null
    var binary = false
}
