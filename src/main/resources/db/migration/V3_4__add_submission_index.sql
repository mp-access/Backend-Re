CREATE INDEX idx_submission_user_created_desc
  ON submission (evaluation_id, user_id, created_at DESC);

CREATE INDEX idx_submission_file_submission_id
  ON submission_file (submission_id);

CREATE INDEX idx_submission_file_task_file_id
  ON submission_file (task_file_id);
