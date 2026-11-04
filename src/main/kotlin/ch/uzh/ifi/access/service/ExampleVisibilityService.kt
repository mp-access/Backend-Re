package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.dto.InteractiveExampleDTO
import ch.uzh.ifi.access.repository.ExampleRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Cached visibility queries for the examples of a course. A bean of its own, so the caches engage when
 * [ExampleService] calls them (previously that needed a self-injected proxy).
 *
 * The cache names keep their "ExampleService." prefix: they are configured in CacheConfig and evicted by
 * ExampleService when examples are published, terminated or reset.
 */
@Service
class ExampleVisibilityService(
    private val exampleRepository: ExampleRepository,
    @Value("\${examples.grace-period}") private val gracePeriod: Long,
) {
    @Cacheable(value = ["ExampleService.getInteractiveExampleSlug"], key = "#courseSlug")
    fun getInteractiveExampleSlug(courseSlug: String): InteractiveExampleDTO {
        val now = LocalDateTime.now()
        val interactiveExampleSlug = exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
            .firstOrNull { Task.isInteractivePeriod(it.start, it.end, now, gracePeriod) }
            ?.slug
        return InteractiveExampleDTO(interactiveExampleSlug)
    }

    @Cacheable(value = ["ExampleService.studentHasVisibleExamples"], key = "#courseSlug")
    fun studentHasVisibleExamples(courseSlug: String): Boolean =
        exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug).any { it.start != null }

    @Cacheable(value = ["ExampleService.supervisorHasVisibleExamples"], key = "#courseSlug")
    fun supervisorHasVisibleExamples(courseSlug: String): Boolean =
        exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug).isNotEmpty()
}
