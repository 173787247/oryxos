-- V14 团队目录（#539）：opaque team_id 的展示名。成员关系仍在 V12 team_memberships。
-- V13 已被 #537 占用；本刀取 V14。

CREATE TABLE IF NOT EXISTS teams (
    team_id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_teams_display_name ON teams (display_name);
