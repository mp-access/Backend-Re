package ch.uzh.ifi.access.model.dto

data class LLMConfigDTO(
    var submission: String? = null,
    var solution: String? = null,
    var rubrics: String? = null,
    var cot: Boolean = false,
    var voting: Int = 1,
    var examples: String? = null,
    var prompt: String? = null,
    var pre: String? = null,
    var post: String? = null,
    var temperature: Double? = null,
    var model: String? = null,
    var modelFamily: String? = null,
    var maxPoints: Double? = null
) 