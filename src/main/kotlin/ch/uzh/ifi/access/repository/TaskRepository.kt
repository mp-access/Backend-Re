package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.projections.TaskWorkspace
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.security.access.prepost.PostAuthorize

interface TaskRepository : JpaRepository<Task?, Long?> {
    // TODO: visibility based on date
    //@PostAuthorize("hasRole(#courseSlug + '-assistant') or (hasRole(#courseSlug) and returnObject.get().published)")
    @PostAuthorize("hasRole(#courseSlug + '-assistant') or (hasRole(#courseSlug))")
    fun findByAssignment_Course_SlugAndAssignment_SlugAndSlug(
        courseSlug: String?,
        assignmentSlug: String?,
        taskSlug: String?
    ): TaskWorkspace?

    fun getByAssignment_Course_SlugAndAssignment_SlugAndSlug(
        courseSlug: String?,
        assignmentSlug: String?,
        taskSlug: String?
    ): Task?

    // Id-only resolution used by the cached TaskLookupService (hot submission path).
    @Query("select t.id from Task t where t.assignment.course.slug = ?1 and t.assignment.slug = ?2 and t.slug = ?3")
    fun findTaskIdBySlugs(courseSlug: String, assignmentSlug: String, taskSlug: String): Long?

    fun existsByAssignment_Course_SlugAndAssignment_SlugAndSlug(
        courseSlug: String?,
        assignmentSlug: String?,
        taskSlug: String?
    ): Boolean
}
