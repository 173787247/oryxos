-- V26 让「恰好一次」可精确验证：把到点时刻（理论触发值）落进执行历史。
--
-- 此前 task_executions 只有 started_at —— 那是**执行开始时的墙钟**（AgentScheduler
-- 在认领之后取 Instant.now()）。于是「同一次到点被执行了两次」与「两次相邻到点的执行
-- 都被拖慢、started_at 落进同一秒」在数据上无法区分，门禁只能按秒分桶近似判断，
-- 会把后者误报成双发（issue #882）。
--
-- fire_time 是调度时算出的理论触发时刻（整 2 秒的日历点，微秒为零），也是到点认领的
-- CAS 值。写入它之后，「同一 fire_time 有两条执行」才是双发的精确判据。
--
-- 可空：executeIfCurrent 路径（手动/补跑）没有到点，写入 NULL。

ALTER TABLE task_executions ADD COLUMN fire_time TIMESTAMP;
