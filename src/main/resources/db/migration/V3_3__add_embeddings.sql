alter table submission
    add column embedding JSON;

update submission set embedding = '[]'::json;

alter table submission
    alter column embedding set not null;
