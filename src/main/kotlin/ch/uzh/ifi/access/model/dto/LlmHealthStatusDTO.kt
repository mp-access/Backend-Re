package ch.uzh.ifi.access.model.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class LlmHealthStatusDTO (
    val status: String,
    @JsonProperty("model_loaded")
    val modelLoaded: Boolean
)
