package ch.uzh.ifi.access.aggregates

import ch.uzh.ifi.access.performance.DumpAvailabilityCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

// The property tests over the WHOLE dataset: for every stored aggregate
// value, the sum recomputed from the facts must match (float tolerance,
// never ==), and every fact pair must have its row. Read-only. This is the
// consistency safety net: if the backfill or any write hook has a hole,
// one of these counts stops being zero.
@ExtendWith(DumpAvailabilityCondition::class)
@SpringBootTest
class AggregateConsistencyTests(
    @Autowired val jdbc: JdbcTemplate,
) {

    // guards the vacuous-truth trap: zero mismatches proves nothing if the
    // tables are empty
    @Test
    fun `aggregate tables are not empty`() {
        val assignmentRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM assignment_evaluation", Long::class.java)!!
        val courseRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM course_evaluation", Long::class.java)!!
        assertTrue(assignmentRows > 0) { "assignment_evaluation is empty: no backfill?" }
        assertTrue(courseRows > 0) { "course_evaluation is empty: no backfill?" }
    }

    @Test
    fun `every stored assignment aggregate equals the sum recomputed from the facts`() {
        val mismatches = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM assignment_evaluation ae
            LEFT JOIN (
                SELECT e.user_id, t.assignment_id, SUM(e.best_score) AS points
                FROM evaluation e
                JOIN task t ON e.task_id = t.id
                WHERE t.assignment_id IS NOT NULL AND e.best_score IS NOT NULL
                  AND e.id IN (SELECT MAX(e2.id) FROM evaluation e2 GROUP BY e2.task_id, e2.user_id)
                GROUP BY e.user_id, t.assignment_id
            ) facts ON facts.user_id = ae.user_id AND facts.assignment_id = ae.assignment_id
            WHERE ABS(ae.points - COALESCE(facts.points, 0)) > 1e-9
        """, Long::class.java)!!
        assertEquals(0L, mismatches)
    }

    @Test
    fun `every fact pair has its assignment aggregate row`() {
        val missing = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM (
                SELECT e.user_id, t.assignment_id
                FROM evaluation e
                JOIN task t ON e.task_id = t.id
                WHERE t.assignment_id IS NOT NULL AND e.best_score IS NOT NULL
                  AND e.id IN (SELECT MAX(e2.id) FROM evaluation e2 GROUP BY e2.task_id, e2.user_id)
                GROUP BY e.user_id, t.assignment_id
            ) facts
            LEFT JOIN assignment_evaluation ae
              ON ae.user_id = facts.user_id AND ae.assignment_id = facts.assignment_id
            WHERE ae.id IS NULL
        """, Long::class.java)!!
        assertEquals(0L, missing) {
            "$missing (user, assignment) pairs with graded evaluations have no assignment_evaluation row " +
            "(facts written by code without the aggregate hook? re-run V3_8 or recomputeAggregatesForCourse). First ones:\n" +
            describeMissingAssignmentRows()
        }
    }

    @Test
    fun `every stored course aggregate equals the sum of its assignment aggregates`() {
        val mismatches = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM course_evaluation ce
            LEFT JOIN (
                SELECT ae.user_id, a.course_id, SUM(ae.points) AS points
                FROM assignment_evaluation ae
                JOIN assignment a ON ae.assignment_id = a.id
                GROUP BY ae.user_id, a.course_id
            ) lvl ON lvl.user_id = ce.user_id AND lvl.course_id = ce.course_id
            WHERE ABS(ce.points - COALESCE(lvl.points, 0)) > 1e-9
        """, Long::class.java)!!
        assertEquals(0L, mismatches)
    }

    @Test
    fun `every user with assignment aggregates has its course aggregate row`() {
        val missing = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM (
                SELECT ae.user_id, a.course_id
                FROM assignment_evaluation ae
                JOIN assignment a ON ae.assignment_id = a.id
                GROUP BY ae.user_id, a.course_id
            ) lvl
            LEFT JOIN course_evaluation ce
              ON ce.user_id = lvl.user_id AND ce.course_id = lvl.course_id
            WHERE ce.id IS NULL
        """, Long::class.java)!!
        assertEquals(0L, missing)
    }

    // Lists the offending (course, user, assignment) triples for a failure message,
    // so the diagnosis is in the test log instead of a manual psql session.
    private fun describeMissingAssignmentRows(limit: Int = 10): String {
        val rows = jdbc.queryForList("""
            SELECT c.slug AS course, facts.user_id, facts.assignment_id
            FROM (
                SELECT e.user_id, t.assignment_id
                FROM evaluation e
                JOIN task t ON e.task_id = t.id
                WHERE t.assignment_id IS NOT NULL AND e.best_score IS NOT NULL
                  AND e.id IN (SELECT MAX(e2.id) FROM evaluation e2 GROUP BY e2.task_id, e2.user_id)
                GROUP BY e.user_id, t.assignment_id
            ) facts
            JOIN assignment a ON a.id = facts.assignment_id
            JOIN course c ON c.id = a.course_id
            LEFT JOIN assignment_evaluation ae
              ON ae.user_id = facts.user_id AND ae.assignment_id = facts.assignment_id
            WHERE ae.id IS NULL
            ORDER BY c.slug, facts.user_id
            LIMIT $limit
        """)
        return rows.joinToString("\n") { "  ${it["course"]} / ${it["user_id"]} / assignment ${it["assignment_id"]}" }
    }
}
