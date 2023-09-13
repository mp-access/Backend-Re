package ch.uzh.ifi.access.model.dto

import ch.uzh.ifi.access.projections.EvaluationSummary
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_EMPTY)
class TaskProgressDTO (
    val userId: String? = null,
    // TODO: should be slug
    val url: String? = null,
    val title: String? = null,
    val bestScore: Double? = null,
    val maxPoints: Double? = null,
    val remainingAttempts: Int? = null,
    val maxAttempts: Int? = null,
    val submissions: List<EvaluationSummary> = listOf()
)

