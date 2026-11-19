-- Course API keys expire 12 months after they were created (or regenerated).
alter table course
  add column api_key_created_at timestamp,
  add column api_key_expires_at timestamp;

-- Keys that already exist get a fresh 12-month validity starting now.
update course
   set api_key_created_at = now(),
       api_key_expires_at = now() + interval '12 months'
 where api_key_hash is not null;
