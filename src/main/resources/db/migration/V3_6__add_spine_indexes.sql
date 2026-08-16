-- Add indexes on the foreign-key / filter "spine" columns.
--
-- PostgreSQL does not auto-create indexes for FK constraints. V1_1 defined the FK
-- constraints but no supporting indexes, so the points queries (evaluation -> task ->
-- assignment -> course) fall back to sequential scans on the evaluation table
-- (148k rows in the production dump). Measured with EXPLAIN (ANALYZE, BUFFERS):
--   getTotalPoints            8.2 ms  -> 1.0 ms   (Seq Scan -> Index Scan)
--   getParticipantsWithPoints 862 ms  -> 58 ms    (Parallel Seq Scan -> Index Scan)
-- See 04-repository-review/03-performance.md (P1) and 04-data-model-and-persistence.md (D1).
--
-- IF NOT EXISTS keeps this idempotent if any index was created manually during analysis.

-- evaluation: the hot path. (user_id, task_id) serves both the per-user points filter and
-- the "MAX(id) ... GROUP BY task_id" latest-evaluation subquery; task_id serves the joins.
CREATE INDEX IF NOT EXISTS idx_eval_user_task ON evaluation (user_id, task_id);
CREATE INDEX IF NOT EXISTS idx_eval_task      ON evaluation (task_id);

-- spine joins: task -> assignment -> course
CREATE INDEX IF NOT EXISTS idx_task_assignment       ON task (assignment_id);
CREATE INDEX IF NOT EXISTS idx_task_course           ON task (course_id);
CREATE INDEX IF NOT EXISTS idx_assignment_course     ON assignment (course_id);

-- *_information map loads (one SELECT per parent during projection serialization)
CREATE INDEX IF NOT EXISTS idx_course_information_course        ON course_information (course_id);
CREATE INDEX IF NOT EXISTS idx_assignment_information_assignment ON assignment_information (assignment_id);
CREATE INDEX IF NOT EXISTS idx_task_information_task            ON task_information (task_id);

-- task_file: findByTask_IdAndEnabledTrue... (task workspace / instructions)
CREATE INDEX IF NOT EXISTS idx_task_file_task ON task_file (task_id);

-- @ElementCollection join tables used by findCoursesForUser's EXISTS subqueries
CREATE INDEX IF NOT EXISTS idx_course_registered_students_course ON course_registered_students (course_id);
CREATE INDEX IF NOT EXISTS idx_course_assistants_course          ON course_assistants (course_id);
CREATE INDEX IF NOT EXISTS idx_course_supervisors_course         ON course_supervisors (course_id);
