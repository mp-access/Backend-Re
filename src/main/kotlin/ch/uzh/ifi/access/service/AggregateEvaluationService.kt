package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Evaluation
import ch.uzh.ifi.access.repository.AssignmentEvaluationRepository
import ch.uzh.ifi.access.repository.CourseEvaluationRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Keeps the aggregate tables (assignment_evaluation / course_evaluation)
// in sync with evaluation. Lives in its own class on purpose:
// @Transactional works through a Spring proxy, so a transactional method
// called from inside the same class would silently run WITHOUT a
// transaction.
@Service
class AggregateEvaluationService(
    private val evaluationRepository: EvaluationRepository,
    private val assignmentEvaluationRepository: AssignmentEvaluationRepository,
    private val courseEvaluationRepository: CourseEvaluationRepository,
) {

    // Persists the evaluation AND refreshes the student's two aggregate
    // rows in ONE transaction: a crash between save and recompute cannot
    // leave a new best_score with a stale aggregate. Called only after
    // the container has finished, so row locks are never held during
    // execution. Lock order: assignment row first, then course row — the
    // same order as every other writer, so writers cannot deadlock.
    @Transactional
    fun saveWithAggregates(evaluation: Evaluation, assignmentId: Long?, courseId: Long?) {
        evaluationRepository.save(evaluation)
        val userId = evaluation.userId ?: return
        if (assignmentId == null) return // example tasks stay outside the aggregates
        assignmentEvaluationRepository.upsertAndLock(userId, assignmentId)
        assignmentEvaluationRepository.recomputePoints(userId, assignmentId)
        if (courseId == null) return
        courseEvaluationRepository.upsertAndLock(userId, courseId)
        courseEvaluationRepository.recomputePoints(userId, courseId)
    }
}
