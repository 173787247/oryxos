-- V27（#1010 的 follow-up）：审计看板的窗口查询要能走索引，而不是全表扫描。
--
-- 两张审计表 append-only，只有 session_id / profile_name / trace_id 索引；而看板的
-- 汇总/分组/明细全部按 created_at between 过滤。默认窗口 30 天，表只会变大，所以
-- 每次看板加载都是一次全表扫描。
--
-- 复合 (created_at, id) 而不是裸 (created_at)：
--   * 窗口过滤与分组只用到左前缀 created_at，两者等价；
--   * 明细是 ORDER BY created_at DESC, id DESC LIMIT n，复合索引让并列时刻也直接
--     按 id 排序（SQLite 里 INTEGER PRIMARY KEY 就是 rowid，索引天然带它，实测
--     EXPLAIN QUERY PLAN 为 COVERING INDEX 且无 temp B-tree；PostgreSQL 侧裸
--     (created_at) 还需要一次 Incremental Sort 处理同刻并列）。
--
-- 锁成本：CREATE INDEX 取写锁建索引，期间阻塞写（SQLite 整库单写）。不需要回填数据，
-- 索引建完即完整。SQLite 侧与 postgresql/V27__audit_created_at_index.sql 逐字同义。
CREATE INDEX IF NOT EXISTS idx_llm_calls_created_at_id ON llm_calls (created_at, id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_created_at_id
    ON tool_invocations (created_at, id);
