package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.projections.SubmissionEmbedding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface InteractiveSubmissionStats {
    fun getTestsPassed(): String?
    fun getPoints(): Double?
    fun getHasEmbedding(): Boolean
}

interface SubmissionRepository : JpaRepository<Submission?, Long?> {

    @Query(
        nativeQuery = true, value = """
            SELECT CAST(s.tests_passed AS text) AS testsPassed, s.points AS points,
                   (COALESCE(cardinality(s.embedding), 0) > 0) AS hasEmbedding
            FROM submission s JOIN evaluation e ON e.id = s.evaluation_id
            WHERE e.task_id = :taskId AND s.command = 'GRADE'
              AND s.created_at >= :exampleStart AND s.created_at <= :exampleEnd
        """
    )
    fun findInteractiveExampleSubmissionStats(
        @Param("taskId") taskId: Long,
        @Param("exampleStart") exampleStart: LocalDateTime,
        @Param("exampleEnd") exampleEnd: LocalDateTime
    ): List<InteractiveSubmissionStats>

    @Query(
        value = """
            SELECT s 
            FROM Submission s 
            JOIN FETCH s.evaluation e 
            JOIN FETCH e.task t 
            WHERE t.id = :taskId 
            AND s.userId = :userId 
            ORDER BY s.createdAt DESC
        """
        )
    fun findByEvaluation_Task_IdAndUserIdOrderByCreatedAtDesc(@Param("taskId") taskId: Long, @Param("userId") userId: String): List<Submission>

    @Query(
        value = """
        SELECT s FROM Submission s
        JOIN FETCH s.evaluation e
        WHERE s.evaluation.task.id = :taskId
          AND s.command = :command
          AND s.createdAt >= :exampleStart
          AND s.createdAt <= :exampleEnd
    """
    )
    fun findInteractiveExampleSubmissions(
        @Param("taskId") taskId: Long?,
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
