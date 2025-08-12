package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.projections.SubmissionEmbedding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.security.access.prepost.PostFilter
import java.time.LocalDateTime

interface SubmissionRepository : JpaRepository<Submission?, Long?> {

    @PostFilter(
        "not filterObject.graded or " +
                "hasRole(" +
                "(" +
                "filterObject.evaluation.task.assignment != null ? " +
                "filterObject.evaluation.task.assignment.course.slug : " +
                "filterObject.evaluation.task.course.slug" +
                ") + '-assistant'" +
                ")"
    )
    fun findByEvaluation_Task_IdAndUserId(taskId: Long?, userId: String?): List<Submission>

    @PostFilter(
        "not hasRole(" +
                "(" +
                "filterObject.evaluation.task.assignment != null ? " +
                "filterObject.evaluation.task.assignment.course.slug : " +
                "filterObject.evaluation.task.course.slug" +
                ") + '-assistant'" +
        ")"
    )
    fun findByEvaluation_Task_IdAndUserIdAndCommand(
        taskId: Long?,
        userId: String?,
        command: Command?
    ): List<Submission>

    @Query(value = """
        SELECT s FROM Submission s
        WHERE s.evaluation.task.id = :taskId
          AND s.userId IN :userIds
          AND s.command = :command
          AND s.createdAt >= :exampleStart
          AND s.createdAt <= :exampleEnd
    """)
    fun findInteractiveExampleSubmissions(
        @Param("taskId") taskId: Long?,
        @Param("userIds") userIds: List<String?>,
        @Param("command") command: Command,
        @Param("exampleStart") exampleStart: LocalDateTime,
        @Param("exampleEnd") exampleEnd: LocalDateTime
    ): List<Submission>

    @Query("SELECT DISTINCT s.userId FROM Submission s WHERE s.evaluation.task.assignment.course.id=:courseId AND s.createdAt > :start")
    fun countOnlineByCourse(courseId: Long?, start: LocalDateTime?): List<String>

    @Modifying
    @Query("UPDATE Submission s SET s.userId = :userId WHERE s.userId IN :name")
    fun updateUserId(@Param("name") names: List<String>, @Param("userId") userId: String): Int

    fun findByIdIn(submissionIds: Collection<Long>): List<SubmissionEmbedding>
}
