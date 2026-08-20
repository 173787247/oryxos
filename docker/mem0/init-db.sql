-- mem0 server 的应用库（用户/鉴权/请求日志/配置 overrides）——记忆向量另落主库（POSTGRES_DB）。
-- 挂载到 postgres 的 docker-entrypoint-initdb.d，仅首次初始化数据卷时执行。
SELECT 'CREATE DATABASE mem0_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mem0_app')\gexec
