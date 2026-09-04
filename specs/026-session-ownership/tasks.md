# Tasks: session 归属与多副本正确性（Session Ownership）

**Input**: Design documents from `/specs/026-session-ownership/`
**Prerequisites**: plan.md、research.md（R1~R12）、data-model.md、contracts/coordination.md、quickstart.md

**组织说明**: CAS 协调存储（表 + 契约 + 双库测试）是三个故事共同地基，归 Foundational；fail-fast 也归 Foundational（cluster 开关的安全前置）。正确性类特性测试必需（spec SC 明确双副本断言），IT 用双上下文 + 共享 zonky PG（025 手法）。

## Phase 1: Setup

- [ ] T001 新建 oryxos-core/src/main/java/io/oryxos/core/cluster/ClusterProperties.java（`oryxos.cluster.*`：enabled 默认 false / instance-id 缺省自动生成 主机名-pid / lease-ttl 30s / heartbeat-interval 缺省 TTL/3 / poll-interval 500ms / wait-timeout 120s；owner() = instanceId@startEpochMillis）；OryxOsRuntime.java:150 的 @EnableConfigurationProperties 数组注册
- [ ] T002 [P] 新建 oryxos-storage/src/main/resources/db/migration/{sqlite,postgresql}/V6__coordination.sql：session_turn_leases / channel_event_receipts / channel_leases / instances 四表 + scheduled_tasks 加 claimed_fire_time、claimed_by 可空两列（纯 SQL 加列两库通用；类型按 data-model，PG 侧 TIMESTAMPTZ）

## Phase 2: Foundational（CAS 协调存储与安全前置——阻塞所有故事)

- [ ] T003 新建 oryxos-core/src/main/java/io/oryxos/core/cluster/CoordinationStore.java 契约：turn 三式（tryAcquireTurn/renewTurn/releaseTurn，返回布尔=rowcount 语义）+ claimFireTime(scheduleId, fireTime, owner) + markReceipt(receiptKey) + tryAcquireChannel/renewChannel/releaseChannel + heartbeat(instance) + listInstances() + activeTurnLeases() + purgeExpired(receiptTtl)；时间基准语义写进 Javadoc（DB CURRENT_TIMESTAMP）
- [ ] T004 [P] 新建 oryxos-storage 四实体与 Repository：TurnLeaseEntity/ChannelEventReceipt/ChannelLeaseEntity/InstanceHeartbeat（oryxos-storage/src/main/java/io/oryxos/storage/），条件 UPDATE 用 @Modifying @Query（仿 SessionRepository.java:18-32 形态）；ScheduledTaskRepository 加 claimFireTime 条件更新方法
- [ ] T005 新建 oryxos-storage/src/main/java/io/oryxos/storage/JpaCoordinationStore.java：CAS 三式实现（INSERT 捕 DataIntegrityViolationException → 条件 UPDATE 抢过期 → false；dbNow 用 SELECT CURRENT_TIMESTAMP）；实现 CoordinationStore
- [ ] T006 新建 CoordinationStore 双库契约测试 oryxos-storage/src/test/java/io/oryxos/storage/CoordinationStoreContractTest.java + Sqlite/Postgres 子类（025 基座）：并发 tryAcquireTurn 恰一胜、过期抢占成功且未过期抢占失败、renew fencing（owner 不符 rowcount=0）、release 只删自己的、markReceipt 冲突判重、claimFireTime 同 fireTime 恰一胜、心跳 upsert
- [ ] T007 新建 oryxos-core/src/main/java/io/oryxos/core/cluster/TurnCoordinator.java（契约 + NOOP 常量恒成功零访问）与 DbTurnCoordinator（acquire 阻塞轮询 poll-interval 至 wait-timeout、TurnLease 句柄含 stillHeld/持有线程登记、续租任务挂 ThreadPoolTaskScheduler 每 TTL/3 renew、失败→中断持有线程并标记句柄失效）+ core 单测（NOOP 语义、等待超时、续租失败中断）
- [ ] T008 [P] 新建 oryxos-cli/src/main/java/io/oryxos/cli/ClusterStartupCheck.java（SmartInitializingSingleton，仿 ProviderStartupCheck 端口前失败）：cluster.enabled=true 时拒绝 jdbc:sqlite: datasource / memory.backend=markdown / knowledge.store=memory，报错指明修正方向；顺修 OryxOsRuntime.java:666 memory.backend 未知值静默回落 → 改为 IllegalStateException（对齐 knowledge.store:433 样板）+ 对应测试
- [ ] T009 oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java 装配：turnCoordinator Bean（enabled ? DbTurnCoordinator : NOOP）、messageDeduplicator Bean 按 enabled 选实现（T011 后接通）、心跳循环 Bean（enabled 时按 heartbeat-interval：heartbeat + purgeExpired，挂既有 ThreadPoolTaskScheduler）
- [ ] T010 Checkpoint：`mvn -q spotless:apply && mvn install` 全绿——单机档（enabled=false）全量既有测试零回归在地基期守住（SC-008 前哨）

## Phase 3: User Story 1 - 同会话有序恰好一次（P1）

**Goal**: 双副本下同会话按序恰好一答、重推跨副本去重、独立会话全并行（FR-001/002/003/006、SC-001/004）
**Independent Test**: 双上下文+PG 经渠道入站路径并发投递与重投，断言答数=消息数、历史有序、零冲突报错

- [ ] T011 [US1] MessageDeduplicator 抽接口（oryxos-core/src/main/java/io/oryxos/core/channel/MessageDeduplicator.java 保留 markIfFirst 签名）+ InMemoryMessageDeduplicator 平移现状实现；三渠道适配器（FeishuChannelAdapter/WeComChannelAdapter/DingTalkChannelAdapter）与 InboundMessageService 的类型声明改接口（行为零变）
- [ ] T012 [P] [US1] 新建 oryxos-core/src/main/java/io/oryxos/core/channel/SharedReceiptDeduplicator.java：进程内一级缓存（复用 InMemory 结构）+ 未见过则 CoordinationStore.markReceipt 判重；单测（本地命中不落库、跨实例语义走 store 桩）
- [ ] T013 [US1] oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java 接线（R1/R3）：sessionLocks 锁内 turnCoordinator.acquire(sessionKey)（等待超 wait-timeout 抛新 TurnWaitTimeoutException——core/cluster 下新异常；IM 路径 InboundMessageService catch 后回专门文案「上一条消息还在处理，请稍候再发」，Web 路径 GlobalExceptionHandler 映射 429）→ 登记持有线程 → 既有流程 → saveIfUnchanged 前 lease.stillHeld() 硬校验（失败抛弃写回走轮次失败路径）→ finally release+unlock；processStateless 零改动
- [ ] T014 [US1] 新建 oryxos-boot/src/test/java/io/oryxos/boot/SessionOwnershipIT.java：双上下文+共享 zonky PG（025 双上下文手法 + cluster.enabled=true 不同 instance-id）——①同会话 20 条交替投两上下文（经 InboundMessageService 模拟渠道入站，017 契约手法）：恰好 20 答、历史有序、零 SessionUpdateConflictException；②同一事件重投两上下文：恰好一答；③30 独立会话并发全完成互不等待；④单机档（enabled=false 单上下文）同场景行为与现状一致；⑤单上下文 + enabled=true：认领/续租/释放自洽、全流程正常（自己抢自己的，spec Edge Case）
- [ ] T015 [US1] Checkpoint：US1 独立可验收（T014 全绿 + T010 复跑全绿）

## Phase 4: User Story 2 - 定时任务恰好一次（P1）

**Goal**: 同一到点恰好一个副本执行；执行中崩溃不补发（FR-005、SC-002）
**Independent Test**: 双上下文短周期任务 10 周期恰好 10 条执行记录

- [ ] T016 [US2] oryxos-core/src/main/java/io/oryxos/core/agent/AgentScheduler.java 接线（R5）：runOnce 在 isEnabled 检查后调 CoordinationStore.claimFireTime(scheduleId, fireTime, owner)，rowcount!=1 静默跳过（另一副本已认领）；**fireTime MUST 取 CronTrigger 计算的理论触发时刻（scheduled execution time，各副本对同一 cron 必然同值）——绝非 Instant.now() 墙钟（各副本不同值会让 CAS 静默失效双发，A1）**，实现上把 trigger 计算的下次触发时刻穿透给 runOnce（或经 TriggerContext）；runNow 不认领（手动触发语义，注释说明）；单机档（NOOP store 恒 true）行为不变
- [ ] T017 [US2] 新建 oryxos-boot/src/test/java/io/oryxos/boot/ScheduleExactlyOnceIT.java：双上下文+PG 注册同一 Agent 短周期任务跑 10 周期——task_executions 恰好 10 条、每 fireTime 恰一条；**另加 fireTime 同值断言：两上下文对同一周期计算出的理论触发时刻一致且认领恰一胜（A1 的 CAS 值来源验证）**；kill 执行中上下文后该周期不被另一上下文补发、下周期正常执行

## Phase 5: User Story 3 - 故障接管与运维可见性（P2）

**Goal**: 崩溃轮失败留痕不重放、健康副本接下一轮、企微连接接管、实例可查（FR-004/007/008、SC-003/005/006）
**Independent Test**: kill 持有上下文→轮标失败→下条消息健康上下文处理；/api/v1/instances 列存活

- [ ] T018 [US3] 悬空轮失败留痕（R2/data-model 回收口径）：session_turn_leases 加 agent_execution_id 列（并入 T002 的 V6 脚本）；AgentService 认领成功后把当前 executionId 写入租约行（IM/管理台路径经 AgentExecutionService 传递，无 execution 的路径存空）；DbTurnCoordinator 抢过期成功时对前任租约的未完结 execution 调 AgentExecutionStore.finish(失败,"副本失联，轮次终止")；**无 execution 的路径（Web 同步/SSE/CLI——崩溃时 HTTP 连接已断、用户已见错误）以回收事件结构化日志 + oryxos_leases_reclaimed_total 指标留痕兜底（I1 口径）**——只补留痕不重放
- [ ] T019 [P] [US3] 心跳与清理落地（T009 骨架完成态）：heartbeat upsert instances、purgeExpired 批删超龄回执（>12h）与死实例行（>3×TTL）；结构化日志（认领/回收/续租失败三类，sessionId 脱敏 + CRLF sanitize 房规）
- [ ] T020 [P] [US3] 新建 oryxos-web/src/main/java/io/oryxos/web/controller/InstanceApiController.java：GET /api/v1/instances——listInstances（含存活判定 last_heartbeat_at 距今 <3×TTL）+ activeTurnLeases（谁在处理哪个会话）；ApiResponse 既有口径 + 控制器测试
- [ ] T021 [US3] 新建 oryxos-core/src/main/java/io/oryxos/core/channel/ChannelLeaseCoordinator.java + ChannelAdminService.startOne 接线（R6）：独连型渠道（企微 TYPE）在 cluster 模式下持 channel_leases 租约才 adapter.start()，未持有登记 STANDBY；后台循环竞争/续租，获租→start、失租→stop；飞书/钉钉不走属主；单机档零变化
- [ ] T022 [US3] 新建 oryxos-boot/src/test/java/io/oryxos/boot/FailoverTakeoverIT.java：①A 处理长轮次中强停（context close 不释放/模拟崩溃）→ 租约过期后 B 抢占、悬空 execution 标失败、用户下条消息 B 正常处理且历史完整；②fencing：暂停 A 的续租（测试钩子或短 TTL）超期 → B 接手 → A 恢复写回被 stillHeld 拒绝（fence_conflict 留痕、库中只有 B 结果）；③模拟独连型渠道属主接管（企微适配器桩）；④GET /api/v1/instances 死活判定

## Phase 6: Polish & 收尾

- [ ] T023 [P] 指标（R11）：oryxos-core MetricsRecorder 加 4 个 default 方法（recordLeaseAcquired/recordLeaseReclaimed/recordFenceConflict(kind) + recordDuplicateDropped(channel)）；MicrometerMetricsRecorder 落 oryxos_leases_acquired_total / oryxos_leases_reclaimed_total / oryxos_fence_conflicts_total / oryxos_duplicates_dropped_total（try/catch DEBUG 房规）；埋点在 DbTurnCoordinator/JpaCoordinationStore 调用侧与 SharedReceiptDeduplicator
- [ ] T024 [P] 文档同步：CLAUDE.md 加 oryxos.cluster 配置段与多副本口径；docs/CliGuide.md 加多副本部署节；config/application.yml.example 加 cluster 注释段；docker-compose.yml 注释更新（025 写的「多副本正确性随 026 交付」此刻兑现）
- [ ] T025 `mvn -q spotless:apply && mvn verify` 全量门禁全绿（两库测试零跳过；OWASP 零新依赖面）
- [ ] T026 quickstart V1~V7 真机走查：单机零回归、双真进程+PG 的同会话/调度/接管/fail-fast 演练、SIGSTOP fencing 抽查、压测锚点两负载数字（SC-007：吞吐 ≥1.8×、单会话时延 ±10%、协调开销 p99 <1%）；飞书真机抽查（凭证可用时）
- [ ] T027 acceptance-report.md 落卷（V1~V7 + SC-001~009 对照 + 压测数字表）

## Dependencies

- Phase 1 → Phase 2 → US1 → US2 → US3 → Polish（US2/US3 依赖 Foundational 的 store 与装配；US3 的 T018 依赖 US1 的 T013 接线）
- T003 阻塞 T004/T005/T007；T005 阻塞 T006；T007/T009 阻塞 T013；T011 阻塞 T012 装配接通
- T002 的 V6 脚本在 T018 需补 agent_execution_id 列——T018 前 V6 未发布可直接改（025 同口径：分支内迁移可改）

## Parallel Example

- Phase 1：T001 / T002 并行
- Foundational：T004 / T008 与 T003 完成后的主线并行
- US3：T019 / T020 并行；T023 / T024 并行

## Implementation Strategy

MVP = Phase 1+2+US1（同会话恰好一次是多副本成立判据；调度与接管其次）。T010/T015 两个 checkpoint 守单机零回归与 US1 可验；压测锚点放走查（不进 CI）。
