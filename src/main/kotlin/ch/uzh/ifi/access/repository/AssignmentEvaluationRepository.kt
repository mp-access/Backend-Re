package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.AssignmentEvaluation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AssignmentEvaluationRepository : JpaRepository<AssignmentEvaluation, Long> {

    // Step 1 of the write path: make sure the row exists and TAKE ITS LOCK.
    // Under READ COMMITTED a blocked writer's subquery would still read the
    // snapshot taken before the lock, so the sum must NOT be computed here:
    // lock first (this statement), recompute in a following statement whose
    // snapshot is born after the lock is acquired. The version bump on
    // conflict enforces the rule that every native write bumps version:
    // a JPA writer holding a stale row then fails its optimistic check
    // instead of silently overwriting points.
    @Modifying
    @Query(
        value = """
            INSERT INTO assignment_evaluation (id, user_id, assignment_id, points, version)
            VALUES (nextval('assignment_evaluation_seq'), :userId, :assignmentId, 0, 0)
            ON CONFLICT (user_id, assignment_id)
            DO UPDATE SET version = assignment_evaluation.version + 1
        """,
        nativeQuery = true,
    )
    fun upsertAndLock(@Param("userId") userId: String, @Param("assignmentId") assignmentId: Long): Int

    // Step 2: idempotent recompute — rewrite the TRUE sum from the facts,
    // never increment. Duplicate-safe: on the historical duplicate
    // (task, user) pairs it only counts the MAX(id) row, the same canonical
    // row getTotalPoints uses. Simplifies once the dedup migration lands.
    @Modifying
    @Query(
        value = """
            UPDATE assignment_evaluation ae
            SET points = (
                SELECT COALESCE(SUM(e.best_score), 0)
                FROM evaluation e
                WHERE e.user_id = ae.user_id
                  AND e.id IN (
                      SELECT MAX(e2.id)
                      FROM evaluation e2
                      JOIN task t2 ON e2.task_id = t2.id
                      WHERE e2.user_id = ae.user_id
                        AND t2.assignment_id = ae.assignment_id
                      GROUP BY e2.task_id
                  )
            )
            WHERE ae.user_id = :userId AND ae.assignment_id = :assignmentId
        """,
        nativeQuery = true,
    )
    fun recomputePoints(@Param("userId") userId: String, @Param("assignmentId") assignmentId: Long): Int
}
