package ch.uzh.ifi.access.model.dto

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_EMPTY)
class AssignmentPointsDTO(
    val userId: String?,
    val ordinalNum: Long?,
    val totalPoints: Double?,
)
