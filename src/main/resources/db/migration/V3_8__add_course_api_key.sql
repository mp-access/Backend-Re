-- Per-course API key for the management endpoints (participants, assistants, supervisors, summary, delete).
-- Only the SHA-256 hex digest of the key is stored, never the key itself.
alter table course
  add column api_key_hash varchar(64);
