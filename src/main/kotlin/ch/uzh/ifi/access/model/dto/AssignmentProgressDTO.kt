package ch.uzh.ifi.access.model.dto

import ch.uzh.ifi.access.projections.EvaluationSummary
import com.fasterxml.jackson.annotation.JsonInclude

class AssignmentProgressDTO (
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val userId: String? = null,
    val url: String? = null,
    val title: String? = null,
    val tasks: List<EvaluationSummary>? = null
)

