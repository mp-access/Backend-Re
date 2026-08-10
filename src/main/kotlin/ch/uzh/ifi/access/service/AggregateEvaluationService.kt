package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Evaluation
import ch.uzh.ifi.access.repository.AssignmentEvaluationRepository
import ch.uzh.ifi.access.repository.CourseEvaluationRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.repository.UserPointsProjection
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

    // Read side: pre-summed points as single row lookups. A missing row
    // (sparse design: no graded submission yet) reads as 0.

    // CURRENTLY UNUSED, kept as documentation of a measured experiment:
    // wiring this into calculateAssignmentPoints only ADDED one query per
    // assignment (+11 on useCourse, no time gain), because the workspace
    // answer is task-granular and every TaskOverview card queries its own
    // points anyway. Becomes useful the day a reader wants assignment
    // totals WITHOUT task details.
    fun assignmentPoints(userId: String, assignmentId: Long): Double =
        assignmentEvaluationRepository.findPoints(userId, assignmentId) ?: 0.0

    fun coursePoints(userId: String, courseSlug: String): Double =
        courseEvaluationRepository.findPointsByCourseSlug(userId, courseSlug) ?: 0.0

    fun coursePointsForUsers(courseSlug: String, userIds: List<String>): List<UserPointsProjection> {
        if (userIds.isEmpty()) return emptyList() // JPQL IN () is not valid
        return courseEvaluationRepository.findPointsByCourse(courseSlug, userIds)
    }

    // Bulk refresh for one whole course, hooked to the course update flow
    // (webhook/pull): a structure change (task points edited, tasks moved
    // or removed) can change what every student's sums should be. Missing
    // rows are backfilled first (a task moved into another assignment
    // creates pairs that never had a row), then everything is recomputed
    // from the facts. Same order as the per-student writer: assignment
    // level first, course level second.
    @Transactional
    fun recomputeAggregatesForCourse(courseId: Long) {
        assignmentEvaluationRepository.backfillMissingForCourse(courseId)
        assignmentEvaluationRepository.recomputeAllForCourse(courseId)
        courseEvaluationRepository.backfillMissingForCourse(courseId)
        courseEvaluationRepository.recomputeAllForCourse(courseId)
    }
}
