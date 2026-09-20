-- V24 cost ledger + pricing version (#476 / 050)

ALTER TABLE llm_pricing ADD COLUMN IF NOT EXISTS price_version BIGINT NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS cost_ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64),
    task_id VARCHAR(512),
    agent_name VARCHAR(255),
    team_id VARCHAR(128),
    provider VARCHAR(64),
    model VARCHAR(128),
    source_kind VARCHAR(16) NOT NULL,
    source_ref VARCHAR(256),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    llm_cost_micros BIGINT NOT NULL DEFAULT 0,
    tool_cost_micros BIGINT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    price_version BIGINT,
    session_id VARCHAR(512),
    trace_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cost_ledger_run ON cost_ledger_entries (run_id);
CREATE INDEX IF NOT EXISTS idx_cost_ledger_agent ON cost_ledger_entries (agent_name);
CREATE INDEX IF NOT EXISTS idx_cost_ledger_team ON cost_ledger_entries (team_id);
CREATE INDEX IF NOT EXISTS idx_cost_ledger_model ON cost_ledger_entries (model);
CREATE INDEX IF NOT EXISTS idx_cost_ledger_trace ON cost_ledger_entries (trace_id);
