package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.security.access.prepost.PostFilter
import java.time.LocalDateTime

// TODO ska: All these functions still expect Tasks - how to deal with this?
interface SubmissionRepository : JpaRepository<Submission?, Long?> {
    @PostFilter("not filterObject.graded or hasRole(filterObject.evaluation.problem.assignment.course.slug + '-assistant')")
    fun findByEvaluation_Problem_IdAndUserId(problemId: Long?, userId: String?): List<Submission>

    @PostFilter("not hasRole(filterObject.evaluation.problem.assignment.course.slug + '-assistant')")
    fun findByEvaluation_Problem_IdAndUserIdAndCommand(
        problemId: Long?,
        userId: String?,
        command: Command?
    ): List<Submission>


    @Query("""
            SELECT DISTINCT s.userId 
            FROM Submission s 
            WHERE TYPE(s.evaluation.problem) = Task
                AND s.evaluation.problem.assignment.course.id = :courseId
                AND s.createdAt > :start
    """)
    fun countOnlineByCourse(courseId: Long?, start: LocalDateTime?): List<String>
}