package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class TaskDTO(
    var slug: String? = null,
    var ordinalNum: Int? = null,
    var information: MutableMap<String, ProblemInformationDTO> = HashMap(),
    var maxPoints: Double? = null,
    var maxAttempts: Int? = null,
    var refill: Int? = null,
    var evaluator: ProblemEvaluatorDTO? = null,
    var files: TaskFilesDTO? = null
)
