package ch.uzh.ifi.access.model.dto

import ch.uzh.ifi.access.projections.EvaluationSummary
import ch.uzh.ifi.access.projections.SubmissionSummary
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_EMPTY)
class TaskProgressDTO(
    val userId: String? = null,
    // TODO: should be slug
    val slug: String? = null,
    val bestScore: Double? = null,
    val maxPoints: Double? = null,
    val remainingAttempts: Int? = null,
    val maxAttempts: Int? = null,
    val information: MutableMap<String, TaskInformationDTO> = mutableMapOf(),
    val submissions: List<SubmissionSummary> = listOf()
)

