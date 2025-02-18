package ch.uzh.ifi.access.model.dto

import com.fasterxml.jackson.annotation.JsonProperty
import lombok.Data

@Data
class RubricDTO (
    @JsonProperty("id")
    var id: String,

    @JsonProperty("title")
    var title: String,

    @JsonProperty("points")
    var points: Double
)

@Data
class FewShotExampleDTO (
    @JsonProperty("answer")
    var answer: String,

    @JsonProperty("points")
    var points: String
)

@Data
class AssistantDTO(
    @JsonProperty("question")
    var question: String,

    @JsonProperty("answer")
    var answer: String,

    @JsonProperty("rubrics")
    var rubrics: List<RubricDTO>? = null,

    @JsonProperty("modelSolution")
    var modelSolution: String? = null,

    @JsonProperty("maxPoints")
    var maxPoints: Double? = 1.0,

    @JsonProperty("minPoints")
    var minPoints: Double? = 0.0,

    @JsonProperty("pointStep")
    var pointStep: Double? = 0.5,

    @JsonProperty("chainOfThought")
    var chainOfThought: Boolean? = true,

    @JsonProperty("votingCount")
    var votingCount: Int? = 1,

    @JsonProperty("temperature")
    var temperature: Double? = 0.2,

    @JsonProperty("fewShotExamples")
    var fewShotExamples: List<FewShotExampleDTO>? = null,

    @JsonProperty("prePrompt")
    var prePrompt: String? = null,

    @JsonProperty("prompt")
    var prompt: String? = null,

    @JsonProperty("postPrompt")
    var postPrompt: String? = null,

    @JsonProperty("llmType")
    var llmType: String? = null,

    @JsonProperty("llmModel")
    var llmModel: String? = null
)