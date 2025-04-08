-------------------
-- TABLE CHANGES --
-------------------

-- problem_file
ALTER TABLE task_file RENAME TO problem_file;
ALTER TABLE problem_file RENAME COLUMN task_id TO problem_id;


-- problem_information
ALTER TABLE task_information RENAME TO problem_information;
ALTER TABLE problem_information RENAME COLUMN task_id TO problem_id;

-- problem
ALTER TABLE task RENAME TO problem;
ALTER TABLE problem ADD COLUMN type VARCHAR(31) NOT NULL DEFAULT 'task';
ALTER TABLE problem ADD COLUMN course_id bigint;

-- submission_file
ALTER TABLE submission_file
    RENAME COLUMN task_file_id TO problem_file_id;

-- course
-- no changes necessary (must be made in the example table).

-- evaluation
ALTER TABLE evaluation
    RENAME COLUMN task_id TO problem_id;

-- submission
-- no changes necessary

------------------------
-- CONSTRAINT CHANGES --
------------------------

-- problem_file
ALTER TABLE IF EXISTS problem_file
    DROP CONSTRAINT IF EXISTS FKk9ikv3hs4cyrgi4ti09b02px0;
ALTER TABLE if EXISTS problem_file
    ADD CONSTRAINT fk_problem_file_problem
    FOREIGN KEY (problem_id) REFERENCES problem;

-- problem_information
ALTER TABLE IF EXISTS problem_information
    DROP CONSTRAINT IF EXISTS FKfq1pi7h983c8o6yluwa1exiak;
ALTER TABLE if EXISTS problem_information
    ADD CONSTRAINT fk_problem_information_problem
        FOREIGN KEY (problem_id) REFERENCES problem;

-- submission_file
ALTER TABLE IF EXISTS submission_file
    DROP CONSTRAINT IF EXISTS FK8q5c4pww9mjmgeopmfeh3nibt;
 ALTER TABLE IF EXISTS submission_file
    ADD CONSTRAINT fk_submission_file_problem_file
    FOREIGN KEY (problem_file_id) REFERENCES problem_file;

-- course
-- no changes necessary (must be made in the problem table).

-- evaluation
ALTER TABLE IF EXISTS evaluation
    DROP CONSTRAINT IF EXISTS FKbbqpi9l6p6x5g13193g09cphf;
ALTER TABLE IF EXISTS evaluation
    ADD CONSTRAINT fk_evaluation_problem
        FOREIGN KEY (problem_id) REFERENCES problem;

-- problem
ALTER TABLE IF EXISTS problem
    ADD CONSTRAINT fk_problem_course
        FOREIGN KEY (course_id) REFERENCES course;
ALTER TABLE problem ALTER COLUMN run_command DROP NOT NULL;
ALTER TABLE problem ALTER COLUMN max_points DROP NOT NULL;
ALTER TABLE problem ALTER COLUMN max_attempts DROP NOT NULL;
ALTER TABLE problem ALTER COLUMN assignment_id DROP NOT NULL;
ALTER TABLE IF EXISTS problem
    ADD CONSTRAINT assignment_or_course_reference CHECK (
        (assignment_id is not null and course_id is null) or
        (assignment_id is null and course_id is not null)
    );