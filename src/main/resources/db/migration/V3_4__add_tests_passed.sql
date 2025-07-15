alter table submission
    add column tests_passed JSON;

update submission
set tests_passed = '[]'::json;

alter table submission
    alter column tests_passed set not null;

