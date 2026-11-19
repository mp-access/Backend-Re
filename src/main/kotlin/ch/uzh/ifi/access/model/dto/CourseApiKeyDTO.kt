package ch.uzh.ifi.access.model.dto

import java.time.LocalDateTime

/** Returned once after creating / regenerating a course API key. [key] is never retrievable again. */
data class CourseApiKeyDTO(
    val key: String,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
)

/** State of the API key of a course, without the key itself. */
data class CourseApiKeyStatusDTO(
    val exists: Boolean,
    val expired: Boolean,
    val createdAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,
)
