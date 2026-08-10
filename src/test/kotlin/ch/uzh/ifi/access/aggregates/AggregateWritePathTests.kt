package ch.uzh.ifi.access.aggregates

import ch.uzh.ifi.access.performance.DumpAvailabilityCondition
import ch.uzh.ifi.access.repository.AssignmentEvaluationRepository
import ch.uzh.ifi.access.repository.CourseEvaluationRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.service.AggregateEvaluationService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

// Tests for the two-step write path (upsert-and-lock + recompute), run
// against the dump and rolled back. A full stored-vs-facts sweep will come
// with the backfill work; here we prove the mechanics: one row per (user,
// assignment) no matter how many upserts, version bumps on conflict, and
// the recompute writes the sum we can compute by hand.
@ExtendWith(DumpAvailabilityCondition::class)
@SpringBootTest
@Transactional
class AggregateWritePathTests(
    @Autowired val assignmentEvaluationRepository: AssignmentEvaluationRepository,
    @Autowired val courseEvaluationRepository: CourseEvaluationRepository,
    @Autowired val entityManager: EntityManager,
    @Autowired val aggregateEvaluationService: AggregateEvaluationService,
    @Autowired val evaluationRepository: EvaluationRepository,
) {

    // a real (user, assignment) pair from the dump, the one with most evaluations
    private fun sampleUserAndAssignment(): Pair<String, Long> {
        val row = entityManager.createNativeQuery("""
            SELECT e.user_id, t.assignment_id
            FROM evaluation e JOIN task t ON e.task_id = t.id
            WHERE t.assignment_id IS NOT NULL AND e.best_score IS NOT NULL
            GROUP BY e.user_id, t.assignment_id
            ORDER BY COUNT(*) DESC, e.user_id LIMIT 1
        """).singleResult as Array<*>
        return row[0] as String to (row[1] as Number).toLong()
    }

    @Test
    fun `upsert keeps one row and bumps version on conflict`() {
        val (userId, assignmentId) = sampleUserAndAssignment()
        // state-independent on purpose: the row may or may not exist already
        // (since the backfill it does), so we measure the DELTA between two
        // upserts instead of assuming a fresh insert
        assignmentEvaluationRepository.upsertAndLock(userId, assignmentId)
        val versionAfterFirst = (entityManager.createNativeQuery("""
            SELECT version FROM assignment_evaluation
            WHERE user_id = :u AND assignment_id = :a
        """).setParameter("u", userId).setParameter("a", assignmentId)
            .singleResult as Number).toLong()
        assignmentEvaluationRepository.upsertAndLock(userId, assignmentId)
        val rows = entityManager.createNativeQuery("""
            SELECT version FROM assignment_evaluation
            WHERE user_id = :u AND assignment_id = :a
        """).setParameter("u", userId).setParameter("a", assignmentId).resultList
        assertEquals(1, rows.size) // ON CONFLICT means still ONE row
        assertEquals(versionAfterFirst + 1, (rows.single() as Number).toLong())
    }

    @Test
    fun `recompute writes the sum computed by hand`() {
        val (userId, assignmentId) = sampleUserAndAssignment()
        val expected = (entityManager.createNativeQuery("""
            SELECT COALESCE(SUM(e.best_score), 0)
            FROM evaluation e
            WHERE e.user_id = :u AND e.id IN (
                SELECT MAX(e2.id) FROM evaluation e2
                JOIN task t2 ON e2.task_id = t2.id
                WHERE e2.user_id = :u AND t2.assignment_id = :a
                GROUP BY e2.task_id
            )
        """).setParameter("u", userId).setParameter("a", assignmentId)
            .singleResult as Number).toDouble()
        assignmentEvaluationRepository.upsertAndLock(userId, assignmentId)
        assignmentEvaluationRepository.recomputePoints(userId, assignmentId)
        val stored = (entityManager.createNativeQuery("""
            SELECT points FROM assignment_evaluation
            WHERE user_id = :u AND assignment_id = :a
        """).setParameter("u", userId).setParameter("a", assignmentId)
            .singleResult as Number).toDouble()
        assertEquals(expected, stored, 1e-9)
    }

    @Test
    fun `course recompute sums the assignment aggregates`() {
        val (userId, assignmentId) = sampleUserAndAssignment()
        val courseId = (entityManager.createNativeQuery(
            "SELECT course_id FROM assignment WHERE id = :a"
        ).setParameter("a", assignmentId).singleResult as Number).toLong()
        assignmentEvaluationRepository.upsertAndLock(userId, assignmentId)
        assignmentEvaluationRepository.recomputePoints(userId, assignmentId)
        courseEvaluationRepository.upsertAndLock(userId, courseId)
        courseEvaluationRepository.recomputePoints(userId, courseId)
        // the invariant, valid in ANY database state (backfilled or not):
        // the course row equals the sum of the user's assignment rows in it
        val expectedCourse = (entityManager.createNativeQuery("""
            SELECT COALESCE(SUM(ae.points), 0) FROM assignment_evaluation ae
            JOIN assignment a ON ae.assignment_id = a.id
            WHERE ae.user_id = :u AND a.course_id = :c
        """).setParameter("u", userId).setParameter("c", courseId)
            .singleResult as Number).toDouble()
        val coursePoints = (entityManager.createNativeQuery(
            "SELECT points FROM course_evaluation WHERE user_id = :u AND course_id = :c"
        ).setParameter("u", userId).setParameter("c", courseId).singleResult as Number).toDouble()
        assertEquals(expectedCourse, coursePoints, 1e-9)
    }

    @Test
    fun `saveWithAggregates refreshes both aggregate rows`() {
        val (userId, assignmentId) = sampleUserAndAssignment()
        val courseId = (entityManager.createNativeQuery(
            "SELECT course_id FROM assignment WHERE id = :a"
        ).setParameter("a", assignmentId).singleResult as Number).toLong()
        val evaluationId = (entityManager.createNativeQuery("""
            SELECT MAX(e.id) FROM evaluation e
            JOIN task t ON e.task_id = t.id
            WHERE e.user_id = :u AND t.assignment_id = :a
        """).setParameter("u", userId).setParameter("a", assignmentId)
            .singleResult as Number).toLong()
        val evaluation = evaluationRepository.findById(evaluationId).orElseThrow()!!
        aggregateEvaluationService.saveWithAggregates(evaluation, assignmentId, courseId)
        val assignmentRows = entityManager.createNativeQuery(
            "SELECT points FROM assignment_evaluation WHERE user_id = :u AND assignment_id = :a"
        ).setParameter("u", userId).setParameter("a", assignmentId).resultList
        val courseRows = entityManager.createNativeQuery(
            "SELECT points FROM course_evaluation WHERE user_id = :u AND course_id = :c"
        ).setParameter("u", userId).setParameter("c", courseId).resultList
        assertEquals(1, assignmentRows.size)
        assertEquals(1, courseRows.size)
    }

    @Test
    fun `bulk course recompute repairs corrupted rows`() {
        val (userId, assignmentId) = sampleUserAndAssignment()
        val courseId = (entityManager.createNativeQuery(
            "SELECT course_id FROM assignment WHERE id = :a"
        ).setParameter("a", assignmentId).singleResult as Number).toLong()
        // make sure the rows exist and are correct, remember the truth
        assignmentEvaluationRepository.upsertAndLock(userId, assignmentId)
        assignmentEvaluationRepository.recomputePoints(userId, assignmentId)
        courseEvaluationRepository.upsertAndLock(userId, courseId)
        courseEvaluationRepository.recomputePoints(userId, courseId)
        val truth = (entityManager.createNativeQuery(
            "SELECT points FROM assignment_evaluation WHERE user_id = :u AND assignment_id = :a"
        ).setParameter("u", userId).setParameter("a", assignmentId).singleResult as Number).toDouble()
        // corrupt the row on purpose, then let the bulk repair it
        entityManager.createNativeQuery(
            "UPDATE assignment_evaluation SET points = points + 99 WHERE user_id = :u AND assignment_id = :a"
        ).setParameter("u", userId).setParameter("a", assignmentId).executeUpdate()
        aggregateEvaluationService.recomputeAggregatesForCourse(courseId)
        val repaired = (entityManager.createNativeQuery(
            "SELECT points FROM assignment_evaluation WHERE user_id = :u AND assignment_id = :a"
        ).setParameter("u", userId).setParameter("a", assignmentId).singleResult as Number).toDouble()
        assertEquals(truth, repaired, 1e-9)
    }
}
