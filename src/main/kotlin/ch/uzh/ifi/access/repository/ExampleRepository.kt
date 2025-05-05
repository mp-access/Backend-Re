package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.projections.TaskWorkspace
import org.springframework.data.jpa.repository.JpaRepository

interface ExampleRepository : JpaRepository<Task?, Long?> {

    fun findByCourse_SlugOrderByOrdinalNumDesc(
        courseSlug: String?
    ): List<TaskWorkspace>

    fun findByCourse_SlugAndSlug(
        courseSlug: String?,
        exampleSlug: String?
    ): TaskWorkspace?

    fun getByCourse_SlugAndSlug(
        courseSlug: String?,
        exampleSlug: String?
    ): Task?
}