alter table submission
    add column embedding DOUBLE PRECISION[];

update submission set embedding = '{}'::DOUBLE PRECISION[];

alter table submission
    alter column embedding set not null;
