package ch.uzh.ifi.access.model.dto;

import lombok.Data;

@Data
class ExampleDTO(
    var slug: String? = null,
    var ordinalNum: Int? = null,
    var information: MutableMap<String, ProblemInformationDTO> = HashMap(),
    var refill: Int? = null,
    var evaluator: ProblemEvaluatorDTO? = null,
    var files: TaskFilesDTO? = null
)
