-- V20 审批交互（044 / #466）：检查点时效字段 + IM 回调收据去重。

ALTER TABLE durable_task_checkpoints ADD COLUMN IF NOT EXISTS ttl_seconds INTEGER;
ALTER TABLE durable_task_checkpoints ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS approval_callback_receipts (
    channel VARCHAR(64) NOT NULL,
    callback_id VARCHAR(255) NOT NULL,
    checkpoint_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (channel, callback_id)
);
