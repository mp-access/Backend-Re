CREATE INDEX idx_submission_evaluation_user_command_created
  ON submission (evaluation_id, user_id, command, created_at);
