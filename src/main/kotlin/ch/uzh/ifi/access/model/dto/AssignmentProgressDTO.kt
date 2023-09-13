package ch.uzh.ifi.access.model.dto

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_EMPTY)
class AssignmentProgressDTO (
    //TODO: rename to slug when olat upgrades
    val userId: String? = null,
    val url: String? = null,
    val title: String? = null,
    val tasks: List<TaskProgressDTO>? = null
)

