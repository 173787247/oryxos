-- V27（#1010 的 follow-up）：审计看板的窗口查询要能走索引，而不是全表扫描。
--
-- 两张审计表 append-only，只有 session_id / profile_name / trace_id 索引；而看板的
-- 汇总/分组/明细全部按 created_at between 过滤。默认窗口 30 天，表只会变大，所以
-- 每次看板加载都是一次全表扫描。
--
-- 复合 (created_at, id) 而不是裸 (created_at)：
--   * 窗口过滤与分组只用到左前缀 created_at，两者等价；
--   * 明细是 ORDER BY created_at DESC, id DESC LIMIT n，复合索引让并列时刻也直接
--     按 id 排序（裸 (created_at) 下同刻按 TID 排序，还需一次 Incremental Sort）。
--
-- 锁成本：CREATE INDEX 在 Flyway 的事务里执行，拿 SHARE 锁 —— 构建期间阻塞写、不阻塞
-- 读；耗时与行数线性（百万级约秒到十几秒）。不需要回填数据。
-- 没有用 CREATE INDEX CONCURRENTLY：它不能在事务块里跑（要关掉当前脚本的事务），且
-- 失败会留下 INVALID 索引，而 IF NOT EXISTS 不会替换它，得配清理/重建逻辑。将来若要求
-- 大表升级零停机，再单独上 CIC + 无效索引清理，本版按仓库既有先例走普通 CREATE INDEX。
CREATE INDEX IF NOT EXISTS idx_llm_calls_created_at_id ON llm_calls (created_at, id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_created_at_id
    ON tool_invocations (created_at, id);
