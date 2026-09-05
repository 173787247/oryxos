-- V6 协调面（026-session-ownership，PostgreSQL 方言）：注释与语义同 sqlite 侧；TIMESTAMPTZ 对齐 V1 口径。

CREATE TABLE IF NOT EXISTS session_turn_leases (
    session_id VARCHAR(512) PRIMARY KEY,
    owner VARCHAR(128) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    agent_execution_id BIGINT
);

CREATE TABLE IF NOT EXISTS channel_event_receipts (
    receipt_key VARCHAR(255) PRIMARY KEY,
    first_seen_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS channel_leases (
    channel_name VARCHAR(128) PRIMARY KEY,
    owner VARCHAR(128) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS instances (
    instance_id VARCHAR(128) PRIMARY KEY,
    epoch BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    last_heartbeat_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS claimed_fire_time TIMESTAMPTZ;
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);
