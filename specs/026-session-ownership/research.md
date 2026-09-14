# Research: session 归属与多副本正确性（Session Ownership）

**Feature**: 026-session-ownership | **Date**: 2026-09-03

现状摸底已完成（入站编排/锁/调度/渠道生命周期/配置面/指标/SSE 线程模型/迁移体系逐文件盘点，行号在案）。裁决 R1~R12：

## R1 认领收口点：AgentService.process 的既有锁位置（契约零改动）

**Decision**: DB turn 租约包在 `AgentService.process`（AgentService.java:65-111）既有 `sessionLocks` 的同一位置——进程内锁拿到后认领 DB 租约（慢路径），finally 先释租约再释进程锁。新契约 `TurnCoordinator` 进 core（`acquire(sessionId) → TurnLease` / `renew` / `release`），`TurnCoordinator.NOOP` 恒成功零 DB 访问为默认（StreamListener.NOOP 惯例又一例）——单机档零行为变化的机制载体。

**Rationale**: 摸底确认四个入口（IM InboundMessageService:222 / Web SessionApiController:98-101 / 调度 AgentScheduler:289 / CLI ChatCommand）全部汇聚于同一个 process，且该方法已含「锁内重读快照 → ReAct → saveIfUnchanged」完整段——在此收口一处覆盖全部入口；`processStateless` 不加锁（群聊无状态路径），天然绕开租约竞争与规划口径一致。

**Alternatives considered**: 在 InboundMessageService.buildInference 插入——只覆盖 IM 入口，Web/调度要另插两处，被否；SessionManager 层——粒度对但它是存储门面，编排语义（等待/中止）不属于它，被否。

## R2 CAS 原语：JPA 插入捕约束冲突 + 条件 UPDATE 抢过期（零方言）

**Decision**: 认领 = ① `INSERT`（JPA save）捕 `DataIntegrityViolationException`（唯一约束冲突的跨库统一异常翻译）→ 成功即持有；② 冲突则 `@Modifying @Query` 条件 UPDATE 抢过期：`UPDATE ... SET owner=:me, lease_until=:until WHERE session_id=:sid AND lease_until < :now`，rowcount==1 即抢到；③ 都失败 → 短轮询等待（poll-interval，等待上限=既有超时口径）。续租 fencing = `UPDATE ... SET lease_until=:until WHERE session_id=:sid AND owner=:me`，rowcount==0 即失去持有。释放 = `DELETE WHERE session_id=:sid AND owner=:me`。时间基准 = `SELECT CURRENT_TIMESTAMP`（两库通用标准 SQL），不是各副本本地时钟。

**Rationale**: `SessionRepository.updateMessagesIfUnchanged`（:18-32）的 `@Modifying` 条件更新先例已被双库契约测试钉死（SessionRepositoryContractTest:115）；`INSERT OR IGNORE`/`ON CONFLICT` 是方言面，捕异常路线零方言。owner=`instanceId@epoch`（epoch=进程启动毫秒）——快速重启的新进程不误继承旧代持有（spec Edge Case 代次隔离）。

## R3 fencing 中止的两道闸（不改 ReActLoop）

**Decision**: 续租器（复用既有 `ThreadPoolTaskScheduler`，OryxOsRuntime:1015 的调度池）按 TTL/3 给活跃租约续期；续租失败 → ① `Thread.interrupt()` 持有线程（process 开始时把当前线程登记进 lease 句柄；虚拟线程阻塞在 LLM/工具 IO 会抛 InterruptedException → 走既有轮次失败路径落审计）；② **写回前校验**——process 在 `saveIfUnchanged` 之前调 `lease.stillHeld()`（一次 owner 条件查询），不持有则抛弃写回并抛轮次失败。两道闸叠加：中断兜「尽快停」，写回前校验兜「绝不写」（假死苏醒场景中断可能没送达，校验是硬闸）。

**Rationale**: SSE 路径同步在请求线程、IM/管理台路径在 triggerAsync 后台线程内同步执行（摸底 §11）——lease 句柄随 process 调用栈显式传递（不用 ThreadLocal，同 021 traceId 跨线程教训）；ReActLoop/StreamListener 契约零改动，改动限 AgentService 方法体。

## R4 去重下沉：接口化 + 两级去重

**Decision**: `MessageDeduplicator` 抽为接口（唯一方法 `markIfFirst` 不变）：`InMemoryMessageDeduplicator`（现状实现，单机档默认）与 `SharedReceiptDeduplicator`（先查进程内一级缓存免 DB 热路径，未见过则 INSERT `channel_event_receipts` 捕冲突判重——冲突即重复丢弃）。装配按 `oryxos.cluster.enabled` 选。三渠道适配器调用点（feishu:242 / wecom:313 / dingtalk:311）只改依赖类型声明，行为零变。回执 TTL 过期行由心跳周期顺手批删（惰性清理）。

**Rationale**: markIfFirst 语义天然幂等且是三渠道共用的唯一判重入口；一级缓存保住热路径（同副本内重复占多数——飞书重推优先同连接）。

## R5 调度恰好一次：scheduled_tasks 加 claim 两列的到点 CAS

**Decision**: `scheduled_tasks` 加 `claimed_fire_time`（TIMESTAMP，可空）+ `claimed_by`（VARCHAR，可空）两列（V6 迁移，纯 SQL 加列两库通用）。runOnce 在既有 tryLock+generation+isEnabled 检查后增加到点认领：`UPDATE scheduled_tasks SET claimed_fire_time=:fire, claimed_by=:me WHERE schedule_id=:id AND (claimed_fire_time IS NULL OR claimed_fire_time < :fire)`，rowcount==1 即本副本执行、否则静默跳过（另一副本已认领本次到点）。fire_time = **CronTrigger 计算的理论触发时刻（scheduled execution time）**——各副本对同一 cron 必然同值故 CAS 值天然一致；MUST NOT 用 Instant.now() 墙钟（各副本不同值会让每个副本都「认领成功」，恰好一次静默失效）。`runNow`（管理台手动触发）不认领：用户显式点击语义即执行，双副本下管理台单入口无并发到点问题。

**Rationale**: 零新表；以到点时刻为 CAS 值使「同一次到点」有天然标识；执行中崩溃不补发（认领已记录，无人重抢同一 fire_time）恰合 spec「不重放」。

## R6 企微渠道连接属主：channel lease + 适配器零改动

**Decision**: 新 `ChannelLeaseCoordinator`（core/channel）：仅对独连型渠道（企微 TYPE）生效——`ChannelAdminService.startOne` 在 enabled 检查（:146 先例）后增加属主判定：cluster 模式下持有 `channel_leases` 租约才 `adapter.start()`，未持有则登记 STANDBY 状态不建连；后台循环（心跳池）续租/竞争——获得租约→start，续租失败/失去→stop。适配器 start/stop 已是契约方法（feishu/wecom/dingtalk 均 synchronized 实现），零改动。飞书/钉钉（集群随机投一型）不走属主，多副本各自建连。

**Rationale**: 摸底确认连接生命周期统一收口在 ChannelAdminService（startAll/startOne/stopAll），是唯一插入点；企微自建重连器（WeComChannelAdapter:222-270）在 stop 后不再触发（既有 stop 语义），无互踢残留。

## R7 集群配置与实例身份：oryxos.cluster.*（默认关）

**Decision**: 新 `ClusterProperties`（`oryxos.cluster.*`，@EnableConfigurationProperties 惯例位 OryxOsRuntime:150）：`enabled`（默认 **false**＝单机档全部现状：NOOP 协调、内存去重、零 DB 协调写）、`instance-id`（缺省自动生成 `主机名-pid` 短串）、`lease-ttl`（默认 30s）、`heartbeat-interval`（TTL/3）、`poll-interval`（默认 500ms）、`wait-timeout`（默认沿用轮次超时）。owner=`instanceId@startEpochMillis`。enabled=true 时全套协调生效（单副本也照常——自己抢自己的，spec Edge Case）。

**Rationale**: 摸底确认全仓无实例标识概念，从零引入；显式开关比「探测多副本」诚实可控（探测不可靠），且给了 fail-fast 检查的判定信号。

## R8 误配 fail-fast：ClusterStartupCheck（端口打开前失败）

**Decision**: 仿 `ProviderStartupCheck`（SmartInitializingSingleton，样板注释明确「在端口打开前失败」）新增 `ClusterStartupCheck`：`cluster.enabled=true` 时校验——datasource url 为 `jdbc:sqlite:` → 拒（指路 PG）；`memory.backend=markdown`（或未知值）→ 拒（指路 sqlite/mem0 档）；`knowledge.store=memory` → 拒。顺带修 memory.backend 未知值静默回落 markdown 的既有缺口（单机档也改为未知值报错——与 knowledge.store:433 的 fail-fast 样板对齐）。

## R9 心跳与运维可见性

**Decision**: `instances` 表（instance_id 主键 + epoch + started_at + last_heartbeat_at）；心跳循环复用既有 ThreadPoolTaskScheduler 按 heartbeat-interval upsert，顺手批删过期回执行（R4）与过期 instances 行（> 3×TTL）。查询面：`GET /api/v1/instances`（web 小端点：全部实例 + 心跳时间 + 存活判定）+ `session_turn_leases` 现役持有查询并入同端点响应（谁在处理什么，FR-008）。管理台页面不新增（REST 先行，页面归后续）。

## R10 新表与迁移：V6__coordination（两 vendor 各一份纯 SQL）

**Decision**: `db/migration/{sqlite,postgresql}/V6__coordination.sql`：`session_turn_leases`（session_id PK, owner, lease_until, acquired_at）、`channel_event_receipts`（receipt_key PK, first_seen_at）、`channel_leases`（channel_name PK, owner, lease_until）、`instances`（instance_id PK, epoch, started_at, last_heartbeat_at）+ `scheduled_tasks` 加 claimed_fire_time/claimed_by 两列（ALTER ADD COLUMN 可空列两库通用纯 SQL）。协调实体+Repository 进 oryxos-storage（`CoordinationStore` 契约进 core、Jpa 实现进 storage——依赖倒置惯例；Redis 第二实现留缝不做）。

## R11 指标与日志

**Decision**: MetricsRecorder 加 default 方法（023 惯例）：`recordLeaseAcquired(kind)` / `recordLeaseReclaimed(kind)` / `recordFenceConflict(kind)` / `recordDuplicateDropped(channel)`（kind=turn/schedule/channel）；Micrometer 侧 `oryxos_leases_acquired_total`、`oryxos_leases_reclaimed_total`、`oryxos_fence_conflicts_total`、`oryxos_duplicates_dropped_total`。认领/回收/续租失败各一条结构化日志（含 sessionId 前缀脱敏 + owner，CRLF sanitize 房规）。

## R12 验收载体：双进程 + zonky PG 的 IT 与压测走查

**Decision**: 正确性自动化 = boot IT（复用 025 双上下文手法：两个 SpringApplicationBuilder 上下文 + 共享 zonky PG）：同会话并发有序恰好一次、重推去重跨上下文、调度恰好一次、kill 上下文后接管。渠道入站路径用 017 契约测试手法（模拟入站事件对 InboundMessageService/适配器投递，不依赖真机）。性能锚点（SC-007）= 走查压测（双真进程 + PG，脚本化并发负载，数字落 acceptance-report），不进 CI 门禁（压测在共享 CI runner 上不稳定）；飞书真机抽查沿 017 凭证手法。

## 不做的（边界重申）

粘性路由/请求转发、消息队列、崩溃轮次自动重放、Redis 协调实现、跨请求 Provider 健康记忆、IM 平台侧连接数管理、管理台集群页面（REST 先行）、runNow 的跨副本互斥（手动触发语义）。
