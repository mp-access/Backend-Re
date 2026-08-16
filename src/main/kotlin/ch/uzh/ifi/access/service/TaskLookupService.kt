package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.repository.TaskRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Resolves a task's primary key from its (course, assignment, task) slug triple, with the result
 * cached in memory. This lives in its OWN bean on purpose: Spring's caching is proxy-based, so if
 * SubmissionService called a @Cacheable method on itself the cache would be silently bypassed.
 *
 * Only the id is cached, never the Task entity — callers load a fresh managed entity by id, because
 * the entity is mutated during submission handling and its lazy collections need the request session.
 *
 * The mapping is effectively immutable: the course importer reuses task rows by slug, so a slug triple
 * keeps the same id for the life of the task. The cache is evicted on course re-import
 * (CourseLifecycle.updateFromDirectory) to cover added or renamed slugs.
 */
@Service
class TaskLookupService(
    private val taskRepository: TaskRepository,
) {
    @Cacheable(
        "TaskLookupService.resolveTaskId",
        key = "#courseSlug + '/' + #assignmentSlug + '/' + #taskSlug",
        unless = "#result == null",
    )
    fun resolveTaskId(courseSlug: String, assignmentSlug: String, taskSlug: String): Long? =
        taskRepository.findTaskIdBySlugs(courseSlug, assignmentSlug, taskSlug)
}
