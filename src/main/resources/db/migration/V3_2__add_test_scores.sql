alter table submission
    add column test_scores JSON;

update submission set test_scores = '[]'::json;

alter table submission
    alter column test_scores set not null;
