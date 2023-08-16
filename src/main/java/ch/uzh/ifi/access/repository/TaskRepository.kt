package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.projections.TaskWorkspace
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.access.prepost.PostAuthorize
import java.util.*

interface TaskRepository : JpaRepository<Task?, Long?> {
    @PostAuthorize("hasRole(#courseSlug + '-assistant') or (hasRole(#courseSlug) and returnObject.get().published)")
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

    fun existsByAssignment_Course_SlugAndAssignment_SlugAndSlug(
        courseSlug: String?,
        assignmentSlug: String?,
        taskSlug: String?
    ): Boolean
}