alter table task
    add column test_names JSON;

update task set test_names = '[]'::json;

alter table task
    alter column test_names set not null;
