# Data Model: session 归属与多副本正确性

**Feature**: 026-session-ownership | **Date**: 2026-09-03

四张新表 + scheduled_tasks 两列（V6__coordination.sql，双 vendor 纯 SQL）。全部协调数据落共享库；单机档（cluster.enabled=false）零协调写。

## 新表

**session_turn_leases**（轮次互斥载体）

| 列 | 类型 | 说明 |
|----|------|------|
| session_id | VARCHAR(512) PK | 会话标识（唯一约束即互斥） |
| owner | VARCHAR(128) NOT NULL | `instanceId@startEpochMillis`（代次隔离快速重启） |
| lease_until | TIMESTAMP NOT NULL | 到期时刻（DB 时间基准） |
| acquired_at | TIMESTAMP NOT NULL | 认领时刻（观测用） |

**channel_event_receipts**（跨副本判重）

| 列 | 类型 | 说明 |
|----|------|------|
| receipt_key | VARCHAR(255) PK | `channelName:messageId`（与现 markIfFirst key 同构） |
| first_seen_at | TIMESTAMP NOT NULL | 首见时刻；> 12h 的行由心跳循环批删（现 TTL 口径） |

**channel_leases**（独连型渠道属主）

| 列 | 类型 | 说明 |
|----|------|------|
| channel_name | VARCHAR(128) PK | 渠道名（仅独连型写入，现阶段=企微） |
| owner | VARCHAR(128) NOT NULL | 同上 |
| lease_until | TIMESTAMP NOT NULL | 到期即可被接管 |

**instances**（心跳与运维可见性）

| 列 | 类型 | 说明 |
|----|------|------|
| instance_id | VARCHAR(128) PK | 副本标识（缺省 `主机名-pid`） |
| epoch | BIGINT NOT NULL | 进程启动毫秒（代次） |
| started_at | TIMESTAMP NOT NULL | 启动时刻 |
| last_heartbeat_at | TIMESTAMP NOT NULL | 最近心跳；> 3×TTL 判死并可清理 |

**scheduled_tasks 加列**：`claimed_fire_time TIMESTAMP`（可空）+ `claimed_by VARCHAR(128)`（可空）——到点认领 CAS 载体。

## CAS 三式（JpaCoordinationStore，零方言）

```text
认领   INSERT(实体 save) → DataIntegrityViolationException 即被占
       → UPDATE ... SET owner=:me, lease_until=:until WHERE key=:k AND lease_until < :now   (rowcount==1 抢过期成功)
续租   UPDATE ... SET lease_until=:until WHERE key=:k AND owner=:me                          (rowcount==0 已失去 → fencing)
释放   DELETE WHERE key=:k AND owner=:me
到点   UPDATE scheduled_tasks SET claimed_fire_time=:fire, claimed_by=:me
       WHERE schedule_id=:id AND (claimed_fire_time IS NULL OR claimed_fire_time < :fire)    (rowcount==1 本副本执行)
判重   INSERT receipt → 冲突即重复丢弃
now    SELECT CURRENT_TIMESTAMP（两库通用；不是副本本地时钟）
```

## 编排接线（对既有类的改动面）

```text
AgentService.process（既有 sessionLocks 内）：
  lock.lock() → lease = turnCoordinator.acquire(sessionKey)   ← 阻塞轮询（poll-interval），超 wait-timeout 抛既有超时口径
    → 登记持有线程；续租器每 TTL/3 renew，失败 → interrupt + lease 标记失效
    → …既有流程（锁内重读→ReAct→retainRecentTurns）…
    → lease.stillHeld() 硬校验 → saveIfUnchanged                ← 不持有：抛弃写回，轮次失败留痕
  finally { turnCoordinator.release(lease); lock.unlock(); }

AgentScheduler.runOnce（isEnabled 之后）：claimFireTime(scheduleId, fireTime) rowcount==1 才 executeLocked
ChannelAdminService.startOne（enabled 检查之后）：独连型 && cluster → ChannelLeaseCoordinator 持约才 adapter.start()
渠道适配器 tryClaim → MessageDeduplicator 接口（装配选 InMemory / SharedReceipt）
```

## 配置面（oryxos.cluster.*，默认关）

```yaml
oryxos:
  cluster:
    enabled: false          # false = 单机档全部现状（NOOP 协调、内存去重、零协调写）
    instance-id: ""         # 缺省自动生成 主机名-pid
    lease-ttl: 30s
    heartbeat-interval: 10s # 缺省 TTL/3
    poll-interval: 500ms    # 同会话等待轮询
    wait-timeout: 120s      # 等待上限，超限按既有超时口径反馈
```

enabled=true 时 ClusterStartupCheck 拒绝：`jdbc:sqlite:` datasource、`memory.backend` 为 markdown/未知、`knowledge.store=memory`——报错指明修正方向（端口打开前失败）。

## 状态与回收

- 轮次：acquire → (renew)* → release；过期未释放 = 持有者已死 → 下一个 acquire 走「抢过期」路径拿到，同时给悬空轮次补一条失败留痕（agent_executions 既有失败口径）；**绝无全量清除**（只有条件抢占，无 DELETE 全表）
- 不重放：抢过期只取得**下一轮**执行权；崩溃轮的用户消息不自动重发
- instances：心跳 upsert；查询时 last_heartbeat_at 距今 > 3×TTL 显示为已死；心跳循环顺带批删超龄 receipts 与 instances 行
