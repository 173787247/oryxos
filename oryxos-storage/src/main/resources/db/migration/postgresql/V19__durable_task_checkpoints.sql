-- V19 耐久任务检查点（043 / #465）：挂起/恢复/幂等重试。

CREATE TABLE IF NOT EXISTS durable_task_checkpoints (
    id VARCHAR(64) PRIMARY KEY,
    execution_id BIGINT,
    session_id VARCHAR(128),
    agent_name VARCHAR(255) NOT NULL,
    state VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    checkpoint_kind VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255),
    tool_call_id VARCHAR(128),
    arguments_json TEXT,
    policy_version VARCHAR(64),
    rule_id VARCHAR(128),
    attempt INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_durable_task_checkpoints_idempotency
    ON durable_task_checkpoints (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_durable_task_checkpoints_state
    ON durable_task_checkpoints (state);
CREATE INDEX IF NOT EXISTS idx_durable_task_checkpoints_execution
    ON durable_task_checkpoints (execution_id);
