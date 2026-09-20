-- V21 Flow runs + steps (046 / #468): durable Flow execution engine.

CREATE TABLE IF NOT EXISTS flow_runs (
    id VARCHAR(64) PRIMARY KEY,
    flow_id VARCHAR(255) NOT NULL,
    flow_version VARCHAR(64) NOT NULL,
    definition_markdown TEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    entry_node_id VARCHAR(128),
    current_node_id VARCHAR(128),
    inputs_json TEXT,
    context_json TEXT,
    last_error VARCHAR(1024),
    attempt INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_flow_runs_state ON flow_runs (state);
CREATE INDEX IF NOT EXISTS idx_flow_runs_flow_id ON flow_runs (flow_id);

CREATE TABLE IF NOT EXISTS flow_steps (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(255) NOT NULL,
    inputs_json TEXT,
    outputs_json TEXT,
    error VARCHAR(1024),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_flow_steps_idempotency ON flow_steps (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_flow_steps_run ON flow_steps (run_id);
