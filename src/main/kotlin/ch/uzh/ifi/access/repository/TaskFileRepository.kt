package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.ProblemFile
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.access.prepost.PostFilter

interface TaskFileRepository : JpaRepository<ProblemFile?, Long?> {
    @Transactional
    @PostFilter("hasRole(filterObject.problem.assignment.course.slug + '-assistant') or filterObject.isPublished")
    fun findByProblem_IdAndEnabledTrueOrderByIdAscPathAsc(problemId: Long?): List<ProblemFile>

    @Transactional
    fun findByProblem_IdAndEnabledTrue(problemId: Long?): List<ProblemFile>
    fun findByProblem_IdAndPath(taskId: Long?, filePath: String?): ProblemFile?
}