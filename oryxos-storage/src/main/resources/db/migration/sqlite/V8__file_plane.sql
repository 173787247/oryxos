-- V8 文件面分布式（027-file-plane）：变更通知总线 + 知识索引恰好一次的三张表。
-- 单机档（oryxos.cluster.enabled=false，缺省）workspace_versions/knowledge_build_claims 零写入；
-- knowledge_generations 单机档照用（显式已提交代次替换 max(generation) 推断，修检索竞态隐患）。

-- workspace_versions：DB 作通知总线、文件作内容载体——4 个域各一行、只 UPDATE 自增不增删行。
-- 写时序纪律：管理写路径文件落盘成功之后才递增（读到新版本号即保证能读到新内容）。
CREATE TABLE IF NOT EXISTS workspace_versions (
    domain VARCHAR(32) PRIMARY KEY,
    version BIGINT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
INSERT OR IGNORE INTO workspace_versions (domain, version, updated_by, updated_at) VALUES
    ('agents', 0, 'migration', CURRENT_TIMESTAMP),
    ('skills', 0, 'migration', CURRENT_TIMESTAMP),
    ('personas', 0, 'migration', CURRENT_TIMESTAMP),
    ('knowledge', 0, 'migration', CURRENT_TIMESTAMP);

-- knowledge_build_claims：某知识库一次索引构建的执行权——kb_name 唯一约束即互斥（026 CAS 形态）；
-- 过期未释放 = 持有者已死，可被条件抢占（接管重建，接管者重新起代）。绝无 startup 全量清除路径。
CREATE TABLE IF NOT EXISTS knowledge_build_claims (
    kb_name VARCHAR(128) PRIMARY KEY,
    owner VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    generation BIGINT NOT NULL
);

-- knowledge_generations：检索唯一可见的已提交代次——每库一行；提交条件化（仍持有认领才生效）。
CREATE TABLE IF NOT EXISTS knowledge_generations (
    kb_name VARCHAR(128) PRIMARY KEY,
    committed_generation BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
