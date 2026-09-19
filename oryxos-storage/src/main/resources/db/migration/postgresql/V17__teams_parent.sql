-- V17 团队父级（#581）：nullable parent_team_id 自引用 FK；不改 decide / teamOwner。
-- 环检测在 TeamCatalogService.setParent（有界上行）；删除父团队时子节点 parent_team_id SET NULL。

ALTER TABLE teams ADD COLUMN IF NOT EXISTS parent_team_id VARCHAR(128) NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_teams_parent'
    ) THEN
        ALTER TABLE teams
            ADD CONSTRAINT fk_teams_parent
            FOREIGN KEY (parent_team_id) REFERENCES teams (team_id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_teams_parent_team_id ON teams (parent_team_id);
