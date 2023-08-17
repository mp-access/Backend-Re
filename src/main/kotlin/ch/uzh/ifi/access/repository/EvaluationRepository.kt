package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Evaluation
import ch.uzh.ifi.access.model.dao.Rank
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.*

interface EvaluationRepository : JpaRepository<Evaluation?, Long?> {
    fun findTopByTask_IdAndUserIdOrderById(taskId: Long?, userId: String?): Evaluation?
    fun findByTask_IdAndBestScoreNotNull(taskId: Long?): List<Evaluation>

    @Query(
        "SELECT new ch.uzh.ifi.access.model.dao.Rank(e.userId, SUM(e.bestScore), AVG(size(e.submissions))) " +
                "FROM Evaluation e WHERE e.task.assignment.course.id=:courseId AND e.submissions IS NOT EMPTY " +
                "AND e.bestScore IS NOT NULL GROUP BY e.userId"
    )
    fun getCourseRanking(courseId: Long?): List<Rank>
}