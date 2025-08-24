package ch.uzh.ifi.access.model.dto

data class EmbeddingRequestBodyDTO(
    val submissionId: Long,
    val codeSnippet: String
)
