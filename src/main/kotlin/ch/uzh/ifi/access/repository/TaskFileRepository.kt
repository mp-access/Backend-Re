package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.TaskFile
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.access.prepost.PostFilter
import java.util.*

interface TaskFileRepository : JpaRepository<TaskFile?, Long?> {
    @Transactional
    // TODO: visibility based on date
    //@PostFilter("hasRole(filterObject.task.assignment.course.slug + '-assistant') or filterObject.published")
    fun findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(taskId: Long?): List<TaskFile>

    @Transactional
    fun findByTask_IdAndEnabledTrue(taskId: Long?): List<TaskFile>
    fun findByTask_IdAndPath(taskId: Long?, filePath: String?): TaskFile?
}