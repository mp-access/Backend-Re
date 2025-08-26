package ch.uzh.ifi.access.model.dto

data class EmbeddingResponseBodyDTO(
    val submissionId: Long,
    val embedding: List<Double>
)
