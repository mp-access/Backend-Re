package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.dto.EmbeddingRequestBodyDTO

data class EmbeddingWithContext(
    val courseSlug: String,
    val exampleSlug: String,
    val embeddingRequestBody: EmbeddingRequestBodyDTO,
    var retryCount: Int = 0,
    val forceComputation: Boolean
)
