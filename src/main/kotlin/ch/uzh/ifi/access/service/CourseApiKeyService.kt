package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.CourseApiKeyDTO
import ch.uzh.ifi.access.model.dto.CourseApiKeyStatusDTO
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.requireBySlug
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDateTime
import java.time.Period
import java.util.*

/**
 * Manages the per-course API keys used by the management endpoints (X-API-Key header).
 *
 * A key is only valid for the course it was issued for, so holding the key of one course
 * grants no access to any other course. Only the SHA-256 digest of a key is persisted.
 * Every key expires [VALIDITY] (12 months) after it was created; afterwards it has to be regenerated.
 * There is no global API key.
 */
@Service
class CourseApiKeyService(
    private val courseRepository: CourseRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    /** True if [providedKey] is the non-expired API key of [courseSlug]. Courses without a key reject every request. */
    fun isValid(courseSlug: String?, providedKey: String?): Boolean {
        if (courseSlug.isNullOrBlank() || providedKey.isNullOrBlank()) return false
        val storedHash = courseRepository.findValidApiKeyHashBySlug(courseSlug, now()) ?: return false
        return MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.UTF_8),
            hash(providedKey).toByteArray(Charsets.UTF_8)
        )
    }

    /** Generates a new random key for [courseSlug], replacing any previous one. The plain key is only returned here. */
    @Transactional
    fun generate(courseSlug: String): CourseApiKeyDTO {
        val bytes = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        setApiKey(courseSlug, key)
        val status = status(courseSlug)
        return CourseApiKeyDTO(key, status.createdAt!!, status.expiresAt!!)
    }

    /**
     * Sets the key of [courseSlug] to [key], valid for 12 months from now (or until [expiresAt] if given);
     * `null` removes it so that no key is accepted anymore.
     */
    @Transactional
    fun setApiKey(courseSlug: String, key: String?, expiresAt: LocalDateTime? = null) {
        val course = courseRepository.requireBySlug(courseSlug)
        val now = now()
        course.apiKeyHash = key?.let { hash(it) }
        course.apiKeyCreatedAt = key?.let { now }
        course.apiKeyExpiresAt = key?.let { expiresAt ?: now.plus(VALIDITY) }
        courseRepository.save(course)
    }

    @Transactional
    fun revoke(courseSlug: String) = setApiKey(courseSlug, null)

    /** Whether [courseSlug] has a key and until when it is valid, without revealing the key. */
    fun status(courseSlug: String): CourseApiKeyStatusDTO {
        val course = courseRepository.requireBySlug(courseSlug)
        val exists = course.apiKeyHash != null
        val expiresAt = course.apiKeyExpiresAt
        return CourseApiKeyStatusDTO(
            exists = exists,
            expired = exists && (expiresAt == null || !expiresAt.isAfter(now())),
            createdAt = course.apiKeyCreatedAt,
            expiresAt = expiresAt,
        )
    }

    private fun hash(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))

    companion object {
        private const val KEY_BYTES = 32
        val VALIDITY: Period = Period.ofMonths(12)
    }
}
