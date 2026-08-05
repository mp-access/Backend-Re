-- Aggregate tables for per-student points at assignment and course level.
-- Pure derived data (denormalization): every value here is recomputable
-- from evaluation.best_score at any time.

create table assignment_evaluation (
  id            bigint not null,
  user_id       varchar(255) not null,
  assignment_id bigint not null,
  points        float(53) not null default 0,
  version       bigint not null default 0,
  primary key (id)
);

create table course_evaluation (
  id        bigint not null,
  user_id   varchar(255) not null,
  course_id bigint not null,
  points    float(53) not null default 0,
  version   bigint not null default 0,
  primary key (id)
);

-- One sequence per table, increment 50: this is what @GeneratedValue on the
-- JPA entities expects (same convention as V1_1's evaluation_seq etc.)
create sequence assignment_evaluation_seq start with 1 increment by 50;
create sequence course_evaluation_seq start with 1 increment by 50;

-- One row per (student, assignment) / (student, course). The write path
-- upserts through these constraints (INSERT ... ON CONFLICT), so they are
-- load-bearing for correctness, not just data hygiene.
alter table assignment_evaluation
    add constraint uq_assignment_evaluation unique (user_id, assignment_id);
alter table course_evaluation
    add constraint uq_course_evaluation unique (user_id, course_id);

-- FKs to the spine. No FK on user_id: the app has no user table of its
-- own (identities live in Keycloak), same as evaluation.user_id.
alter table assignment_evaluation
    add constraint fk_assignment_evaluation_assignment
        foreign key (assignment_id) references assignment;
alter table course_evaluation
    add constraint fk_course_evaluation_course
        foreign key (course_id) references course;

-- The per-course reads ("all rows of one assignment/course") scan by the
-- right-hand column; the unique indexes above lead with user_id and cannot
-- serve those scans.
create index idx_assignment_evaluation_assignment_id
    on assignment_evaluation (assignment_id);
create index idx_course_evaluation_course_id
    on course_evaluation (course_id);
