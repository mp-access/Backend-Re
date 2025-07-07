package ch.uzh.ifi.access.model.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class EmbeddingDTO @JsonCreator constructor(
    @JsonProperty("embedding") val embedding: List<Double>
)
