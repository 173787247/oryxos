-- V6 协调面（026-session-ownership）：多副本正确性的四张协调表 + 调度到点认领两列。
-- 单机档（oryxos.cluster.enabled=false，缺省）零写入——表存在但恒空。

-- session_turn_leases：轮次互斥载体——session_id 唯一约束即互斥；owner=instanceId@epoch（代次隔离）；
-- 过期未释放 = 持有者已死，可被条件抢占（绝无全量清除路径）。agent_execution_id 供悬空轮失败留痕。
CREATE TABLE IF NOT EXISTS session_turn_leases (
    session_id VARCHAR(512) PRIMARY KEY,
    owner VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    acquired_at TIMESTAMP NOT NULL,
    agent_execution_id BIGINT
);

-- channel_event_receipts：入站事件跨副本判重——receipt_key = channelName:messageId（与进程内
-- 去重 key 同构）；INSERT 冲突即重复丢弃；>12h 行由心跳循环批删（原进程内 Map 的 TTL 口径）。
CREATE TABLE IF NOT EXISTS channel_event_receipts (
    receipt_key VARCHAR(255) PRIMARY KEY,
    first_seen_at TIMESTAMP NOT NULL
);

-- channel_leases：独连型渠道（企微）连接属主——持约副本唯一建连；过期即可被接管，永不互踢。
CREATE TABLE IF NOT EXISTS channel_leases (
    channel_name VARCHAR(128) PRIMARY KEY,
    owner VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP NOT NULL
);

-- instances：副本心跳与运维可见性——last_heartbeat_at 距今 >3×TTL 判死；死行由心跳循环清理。
CREATE TABLE IF NOT EXISTS instances (
    instance_id VARCHAR(128) PRIMARY KEY,
    epoch BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    last_heartbeat_at TIMESTAMP NOT NULL
);

-- 调度到点认领（恰好一次）：fireTime = CronTrigger 理论触发时刻（各副本同值），条件更新 rowcount=1 即赢。
ALTER TABLE scheduled_tasks ADD COLUMN claimed_fire_time TIMESTAMP;
ALTER TABLE scheduled_tasks ADD COLUMN claimed_by VARCHAR(128);
