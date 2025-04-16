package ch.uzh.ifi.access.model.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import lombok.Data

enum class Status {
    correct,
    incorrect,
    incomplete
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
class AssistantResponseDTO (
    @JsonProperty("status")
    var status: Status,

    @JsonProperty("feedback")
    var feedback: String,

    @JsonProperty("hint")
    var hint: String? = null,

    @JsonProperty("points")
    var points: Double,

    @JsonProperty("votingResult")
    var votingResult: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
class AssistantEvaluationResponseDTO (
    @JsonProperty("status")
    var status: String,

    @JsonProperty("result")
    var result: AssistantResponseDTO?
)

class TaskIdDTO(
    @JsonProperty("jobId")
    var jobId: String
)