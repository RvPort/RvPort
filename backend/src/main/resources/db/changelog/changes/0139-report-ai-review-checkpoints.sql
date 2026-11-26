--liquibase formatted sql

--changeset rvport:0139-report-ai-review-checkpoints
-- 仅缓存协议校验后的整批结果；配置变化使用另一组检查点，历史事实不可覆盖。
CREATE TABLE report_ai_review_checkpoints (
    task_id uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    plan_digest varchar(64) NOT NULL CHECK (plan_digest ~ '^[0-9a-f]{64}$'),
    configuration_digest varchar(64) NOT NULL CHECK (configuration_digest ~ '^[0-9a-f]{64}$'),
    batch_index integer NOT NULL CHECK (batch_index >= 0),
    results jsonb NOT NULL CHECK (jsonb_typeof(results) = 'array' AND jsonb_array_length(results) > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, plan_digest, configuration_digest, batch_index)
);
