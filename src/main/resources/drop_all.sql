-- to regenerate, run from project root:
-- grep -ir create src/main/resources/db | awk -F':' '{print $2}' | grep -E '^create' | sed -e 's/^create/drop/g' | awk '{print $1, $2, "if exists", $3, "cascade;"}'; echo "drop table if exists flyway_schema_history;"

drop table if exists course_registered_students cascade;
drop table if exists global_file cascade;
drop sequence if exists global_file_seq cascade;
drop table if exists assignment cascade;
drop table if exists assignment_information cascade;
drop table if exists course cascade;
drop table if exists course_assistants cascade;
drop table if exists course_supervisors cascade;
drop table if exists course_information cascade;
drop table if exists evaluation cascade;
drop table if exists evaluator cascade;
drop table if exists event cascade;
drop table if exists submission cascade;
drop table if exists submission_file cascade;
drop table if exists task cascade;
drop table if exists task_file cascade;
drop table if exists task_information cascade;
drop sequence if exists assignment_information_seq cascade;
drop sequence if exists assignment_seq cascade;
drop sequence if exists course_information_seq cascade;
drop sequence if exists course_seq cascade;
drop sequence if exists evaluation_seq cascade;
drop sequence if exists evaluator_seq cascade;
drop sequence if exists event_seq cascade;
drop sequence if exists submission_file_seq cascade;
drop sequence if exists submission_seq cascade;
drop sequence if exists task_file_seq cascade;
drop sequence if exists task_information_seq cascade;
drop sequence if exists task_seq cascade;
drop table if exists result_file cascade;
drop sequence if exists result_file_seq cascade;
drop table if exists flyway_schema_history;
