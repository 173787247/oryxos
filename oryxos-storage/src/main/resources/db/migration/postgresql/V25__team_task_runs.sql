-- V25 Direction I team-task runs: durable persistence for POST/GET /api/v1/tasks/team.

CREATE TABLE IF NOT EXISTS team_task_runs (
    id VARCHAR(64) PRIMARY KEY,
    goal TEXT NOT NULL,
    coordinator VARCHAR(255) NOT NULL,
    plan_raw TEXT,
    workers_json TEXT NOT NULL,
    summary TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_team_task_runs_created ON team_task_runs (created_at);
