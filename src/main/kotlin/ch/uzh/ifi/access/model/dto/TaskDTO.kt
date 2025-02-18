package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class TaskDTO(
    var slug: String? = null,
    var ordinalNum: Int? = null,
    var information: MutableMap<String, TaskInformationDTO> = HashMap(),
    var maxPoints: Double? = null,
    var maxAttempts: Int? = null,
    var refill: Int? = null,
    var evaluator: TaskEvaluatorDTO? = null,
    var files: TaskFilesDTO? = null,
    var llmSubmission: String? = null,
    var llmSolution: String? = null,
    var llmRubrics: String? = null,
    var llmCot: Boolean = false,
    var llmVoting: Int = 1,
    var llmExamples: String? = null,
    var llmPrompt: String? = null,
    var llmPre: String? = null,
    var llmPost: String? = null,
    var llmTemperature: Double? = null,
    var llmModel: String? = null,
    var llmModelFamily: String? = null,
    var llmMaxPoints: Double? = null,
)
