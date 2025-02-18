ALTER TABLE task
    ADD COLUMN IF NOT EXISTS llm_submission TEXT,
    ADD COLUMN IF NOT EXISTS llm_solution TEXT,
    ADD COLUMN IF NOT EXISTS llm_rubrics TEXT,
    ADD COLUMN IF NOT EXISTS llm_cot BOOLEAN,
    ADD COLUMN IF NOT EXISTS llm_voting INTEGER,
    ADD COLUMN IF NOT EXISTS llm_examples TEXT,
    ADD COLUMN IF NOT EXISTS llm_prompt TEXT,
    ADD COLUMN IF NOT EXISTS llm_pre TEXT,
    ADD COLUMN IF NOT EXISTS llm_post TEXT,
    ADD COLUMN IF NOT EXISTS llm_temperature DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS llm_model TEXT,
    ADD COLUMN IF NOT EXISTS llm_model_family TEXT,
    ADD COLUMN IF NOT EXISTS llm_max_points DOUBLE PRECISION;
