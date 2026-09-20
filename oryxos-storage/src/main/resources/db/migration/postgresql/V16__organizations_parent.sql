-- V16 组织父级（#566）：nullable parent_org_id 自引用 FK；不改 decide / orgOwner。
-- 不实现环检测；删除父组织时子节点 parent_org_id SET NULL。

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS parent_org_id VARCHAR(128) NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_organizations_parent'
    ) THEN
        ALTER TABLE organizations
            ADD CONSTRAINT fk_organizations_parent
            FOREIGN KEY (parent_org_id) REFERENCES organizations (org_id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_organizations_parent_org_id ON organizations (parent_org_id);
