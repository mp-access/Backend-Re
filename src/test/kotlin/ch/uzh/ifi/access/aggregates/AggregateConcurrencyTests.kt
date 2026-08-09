package ch.uzh.ifi.access.aggregates

import ch.uzh.ifi.access.performance.DumpAvailabilityCondition
import ch.uzh.ifi.access.repository.AssignmentEvaluationRepository
import ch.uzh.ifi.access.repository.CourseEvaluationRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The guard for the two-writers race: two threads grade the same student on
// two different tasks of the same assignment at the same time, colliding on
// the shared aggregate row. After both commit, the stored aggregate must
// contain BOTH updates. Deliberately NOT @Transactional: the race only
// exists between separate transactions that really commit, so each thread
// opens its own (TransactionTemplate) and the test cleans up after itself
// instead of relying on rollback.
@ExtendWith(DumpAvailabilityCondition::class)
@SpringBootTest
class AggregateConcurrencyTests(
    @Autowired val assignmentEvaluationRepository: AssignmentEvaluationRepository,
    @Autowired val courseEvaluationRepository: CourseEvaluationRepository,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    private lateinit var userId: String
    private var assignmentId = 0L
    private var courseId = 0L
    private var evaluationIds = listOf<Long>()
    private var originalScores = mapOf<Long, Double>()

    companion object {
        private const val ROUNDS = 10
    }

    @BeforeEach
    fun pickSampleAndRememberOriginals() {
        // a student with graded evaluations on at least two tasks of the same
        // assignment: each thread will update one of the two
        val sample = jdbc.queryForMap("""
            SELECT e.user_id AS uid, t.assignment_id AS aid
            FROM evaluation e JOIN task t ON e.task_id = t.id
            WHERE t.assignment_id IS NOT NULL AND e.best_score IS NOT NULL
            GROUP BY e.user_id, t.assignment_id
            HAVING COUNT(DISTINCT e.task_id) >= 2
            ORDER BY COUNT(*) DESC, e.user_id
            LIMIT 1
        """)
        userId = sample["uid"] as String
        assignmentId = (sample["aid"] as Number).toLong()
        courseId = jdbc.queryForObject(
            "SELECT course_id FROM assignment WHERE id = ?", Long::class.java, assignmentId)!!
        evaluationIds = jdbc.queryForList("""
            SELECT MAX(e.id) AS id FROM evaluation e
            JOIN task t ON e.task_id = t.id
            WHERE e.user_id = ? AND t.assignment_id = ? AND e.best_score IS NOT NULL
            GROUP BY e.task_id ORDER BY id LIMIT 2
        """, Long::class.java, userId, assignmentId)
        originalScores = evaluationIds.associateWith {
            jdbc.queryForObject("SELECT best_score FROM evaluation WHERE id = ?", Double::class.java, it)!!
        }
    }

    // no free rollback here: undo our score bumps and drop the aggregate
    // rows. Guarded so that a failure in the setup (before userId exists)
    // does not blow up the cleanup too.
    @AfterEach
    fun restoreDumpState() {
        if (!::userId.isInitialized) return
        originalScores.forEach { (id, score) ->
            jdbc.update("UPDATE evaluation SET best_score = ? WHERE id = ?", score, id)
        }
        jdbc.update("DELETE FROM course_evaluation WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM assignment_evaluation WHERE user_id = ?", userId)
    }

    @Test
    fun `parallel graders of the same student cannot lose points`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(ROUNDS) {
                val barrier = CyclicBarrier(2)
                val workers = evaluationIds.map { evaluationId ->
                    executor.submit {
                        tx.execute {
                            // each thread "grades" its own task: +1 on its own
                            // evaluation row (different rows, no conflict yet)
                            jdbc.update(
                                "UPDATE evaluation SET best_score = best_score + 1 WHERE id = ?",
                                evaluationId)
                            // start gate: both threads arrive here, then hit
                            // the SAME aggregate row together
                            barrier.await(10, TimeUnit.SECONDS)
                            assignmentEvaluationRepository.upsertAndLock(userId, assignmentId)
                            assignmentEvaluationRepository.recomputePoints(userId, assignmentId)
                            courseEvaluationRepository.upsertAndLock(userId, courseId)
                            courseEvaluationRepository.recomputePoints(userId, courseId)
                        }
                    }
                }
                workers.forEach { it.get(30, TimeUnit.SECONDS) } // rethrows worker failures
                // oracle: the sum recomputed from the facts AFTER both commits.
                // If a thread's update was lost to the snapshot race, the
                // stored value is lower than this.
                val expected = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(e.best_score), 0)
                    FROM evaluation e
                    WHERE e.user_id = ? AND e.id IN (
                        SELECT MAX(e2.id) FROM evaluation e2
                        JOIN task t2 ON e2.task_id = t2.id
                        WHERE e2.user_id = ? AND t2.assignment_id = ?
                        GROUP BY e2.task_id
                    )
                """, Double::class.java, userId, userId, assignmentId)!!
                val stored = jdbc.queryForObject(
                    "SELECT points FROM assignment_evaluation WHERE user_id = ? AND assignment_id = ?",
                    Double::class.java, userId, assignmentId)!!
                assertEquals(expected, stored, 1e-9)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
