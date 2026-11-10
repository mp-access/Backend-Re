package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.requireBySlug
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/**
 * Manages the per-course API keys used by the management endpoints (X-API-Key header).
 *
 * A key is only valid for the course it was issued for, so holding the key of one course
 * grants no access to any other course. Only the SHA-256 digest of a key is persisted.
 */
@Service
class CourseApiKeyService(
    private val courseRepository: CourseRepository,
) {
    private val random = SecureRandom()

    /** True if [providedKey] is the API key of [courseSlug]. Courses without a key reject every request. */
    fun isValid(courseSlug: String?, providedKey: String?): Boolean {
        if (courseSlug.isNullOrBlank() || providedKey.isNullOrBlank()) return false
        val storedHash = courseRepository.findApiKeyHashBySlug(courseSlug) ?: return false
        return MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.UTF_8),
            hash(providedKey).toByteArray(Charsets.UTF_8)
        )
    }

    /** Generates a new random key for [courseSlug], replacing any previous one. The plain key is only returned here. */
    @Transactional
    fun generate(courseSlug: String): String {
        val bytes = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        setApiKey(courseSlug, key)
        return key
    }

    /** Sets the key of [courseSlug] to [key]; `null` removes it so that no key is accepted anymore. */
    @Transactional
    fun setApiKey(courseSlug: String, key: String?) {
        val course = courseRepository.requireBySlug(courseSlug)
        course.apiKeyHash = key?.let { hash(it) }
        courseRepository.save(course)
    }

    @Transactional
    fun revoke(courseSlug: String) = setApiKey(courseSlug, null)

    private fun hash(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))

    companion object {
        private const val KEY_BYTES = 32
    }
}
