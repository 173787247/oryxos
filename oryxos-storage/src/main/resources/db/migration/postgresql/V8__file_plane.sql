-- V8 文件面分布式（027-file-plane，PostgreSQL 方言）：注释与语义同 sqlite 侧；TIMESTAMPTZ 对齐 V1 口径。

CREATE TABLE IF NOT EXISTS workspace_versions (
    domain VARCHAR(32) PRIMARY KEY,
    version BIGINT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
INSERT INTO workspace_versions (domain, version, updated_by, updated_at) VALUES
    ('agents', 0, 'migration', CURRENT_TIMESTAMP),
    ('skills', 0, 'migration', CURRENT_TIMESTAMP),
    ('personas', 0, 'migration', CURRENT_TIMESTAMP),
    ('knowledge', 0, 'migration', CURRENT_TIMESTAMP)
ON CONFLICT (domain) DO NOTHING;

CREATE TABLE IF NOT EXISTS knowledge_build_claims (
    kb_name VARCHAR(128) PRIMARY KEY,
    owner VARCHAR(128) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    generation BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_generations (
    kb_name VARCHAR(128) PRIMARY KEY,
    committed_generation BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
