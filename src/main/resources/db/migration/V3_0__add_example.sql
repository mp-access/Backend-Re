ALTER TABLE task ADD COLUMN course_id bigint;

AlTER TABLE task ADD COLUMN start_date timestamp(6);
AlTER TABLE task ADD COLUMN end_date timestamp(6);

ALTER TABLE task
    ADD CONSTRAINT fk_task_course
    FOREIGN KEY (course_id) REFERENCES course;

ALTER TABLE task ALTER COLUMN run_command DROP NOT NULL;
ALTER TABLE task ALTER COLUMN max_points DROP NOT NULL;
ALTER TABLE task
    ALTER COLUMN max_points SET DEFAULT 1;
ALTER TABLE task ALTER COLUMN max_attempts DROP NOT NULL;
ALTER TABLE task
    ALTER COLUMN max_attempts SET DEFAULT 1;
ALTER TABLE task ALTER COLUMN assignment_id DROP NOT NULL;

ALTER TABLE task
    ADD CONSTRAINT assignment_or_course_reference CHECK (
    (assignment_id is not null and course_id is null) or
    (assignment_id is null and course_id is not null)
);