-- V15 组织目录 + teams.org_id（#554）：可选组织元数据；团队可挂 org（可空）。
-- 不改 AuthorizationService.decide / teamOwner ACL。

CREATE TABLE IF NOT EXISTS organizations (
    org_id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_organizations_display_name ON organizations (display_name);

ALTER TABLE teams ADD COLUMN IF NOT EXISTS org_id VARCHAR(128) NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_teams_org'
    ) THEN
        ALTER TABLE teams
            ADD CONSTRAINT fk_teams_org
            FOREIGN KEY (org_id) REFERENCES organizations (org_id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_teams_org_id ON teams (org_id);
