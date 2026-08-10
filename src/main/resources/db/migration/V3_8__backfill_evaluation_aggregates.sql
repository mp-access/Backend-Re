-- Backfill: fill the aggregate tables from the existing evaluation history.
-- Sparse-row design: only (student, assignment) pairs with at least one
-- graded evaluation get a row; readers COALESCE missing rows to 0.
-- Duplicate-safe: on the historical duplicate (task, user) pairs only the
-- MAX(id) row counts — the same canonical row getTotalPoints uses.
-- ON CONFLICT DO NOTHING: rows already written by the live grading hook are
-- already correct (the hook recomputes the full sum from the facts), so the
-- backfill only fills the missing ones.

INSERT INTO assignment_evaluation (id, user_id, assignment_id, points, version)
SELECT nextval('assignment_evaluation_seq'), sums.user_id, sums.assignment_id, sums.points, 0
FROM (
    SELECT e.user_id, t.assignment_id, SUM(e.best_score) AS points
    FROM evaluation e
    JOIN task t ON e.task_id = t.id
    WHERE t.assignment_id IS NOT NULL
      AND e.best_score IS NOT NULL
      AND e.id IN (
          SELECT MAX(e2.id) FROM evaluation e2 GROUP BY e2.task_id, e2.user_id
      )
    GROUP BY e.user_id, t.assignment_id
) sums
ON CONFLICT (user_id, assignment_id) DO NOTHING;

-- Course level second, summed from the assignment level just filled — the
-- same way the write path computes it, so the two levels cannot disagree.
INSERT INTO course_evaluation (id, user_id, course_id, points, version)
SELECT nextval('course_evaluation_seq'), sums.user_id, sums.course_id, sums.points, 0
FROM (
    SELECT ae.user_id, a.course_id, SUM(ae.points) AS points
    FROM assignment_evaluation ae
    JOIN assignment a ON ae.assignment_id = a.id
    GROUP BY ae.user_id, a.course_id
) sums
ON CONFLICT (user_id, course_id) DO NOTHING;
