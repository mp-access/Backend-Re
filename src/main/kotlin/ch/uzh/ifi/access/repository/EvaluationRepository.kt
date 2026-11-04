package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Evaluation
import ch.uzh.ifi.access.projections.EvaluationSummary
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param


interface EvaluationRepository : JpaRepository<Evaluation?, Long?> {
    // TODO: confusing naming; why get/find?
    fun getTopByTask_IdAndUserIdOrderById(taskId: Long?, userId: String?): Evaluation?

    fun findTopByTask_IdAndUserIdOrderById(taskId: Long?, userId: String?): EvaluationSummary?

    fun findAllByTask_IdAndUserIdIn(taskId: Long?, students: Set<String>): List<Evaluation>?

    /** Average best score of all evaluations of a task that have been graded at least once; null if there are none. */
    @Query("SELECT AVG(e.bestScore) FROM Evaluation e WHERE e.task.id = :taskId AND e.bestScore IS NOT NULL")
    fun getAverageBestScore(@Param("taskId") taskId: Long): Double?
}
