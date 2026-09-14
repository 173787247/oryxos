# Implementation Plan: session 归属与多副本正确性（Session Ownership）

**Branch**: `026-session-ownership` | **Date**: 2026-09-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/026-session-ownership/spec.md`

## Summary

一条 CAS 认领原语（JPA 插入捕唯一约束冲突 + `@Modifying` 条件 UPDATE 抢过期——零方言，SessionRepository 先例已被双库测试钉死）套四类协调载体：turn 租约收口在 `AgentService.process` 既有 sessionLocks 位置（四入口自动全覆盖、ReActLoop/契约零改动），续租 fencing 双闸（中断持有线程 + saveIfUnchanged 前 stillHeld 硬校验）；调度以「到点时刻」为 CAS 值在 scheduled_tasks 加两列认领（零新表）；去重接口化下沉 `channel_event_receipts`（进程内一级缓存保热路径）；企微连接属主经 `channel_leases` 在 ChannelAdminService.startOne 收口（适配器零改动）。`oryxos.cluster.enabled` 默认 false＝单机档全部现状（TurnCoordinator.NOOP + 内存去重，零 DB 协调写）；true 时 ClusterStartupCheck 端口前 fail-fast 误配组合（SQLite/markdown 记忆/memory 知识库）。新表走 V6 迁移（两 vendor 纯 SQL）；心跳落 instances 表 + `GET /api/v1/instances` 运维查询；指标挂 MetricsRecorder default 方法（023 惯例）。验收：双上下文 + zonky PG 的正确性 IT + 双真进程压测走查（SC-007 性能锚点落卷）。

## Technical Context

**Language/Version**: Java 21（虚拟线程；中断语义用于 fencing 快停）

**Primary Dependencies**: 零新增（JPA/JdbcTemplate 类路径已在；zonky test 侧已有）

**Storage**: 新表 session_turn_leases / channel_event_receipts / channel_leases / instances + scheduled_tasks 加 2 列（V6__coordination.sql 两 vendor）；时间基准 `SELECT CURRENT_TIMESTAMP`（两库通用）

**Testing**: JUnit 5——storage（CoordinationStore 契约用例双库子类，025 基座复用）、core（TurnCoordinator 语义/去重接口）、boot IT（双上下文+共享 zonky PG：并发有序/去重/调度恰好一次/kill 接管/fencing 中止）；`mvn verify` 全量门禁；压测走查不进 CI

**Target Platform**: Linux server；多副本档 = 双进程 + 共享 PG（容器编排归 027/028）

**Project Type**: Maven 多模块——core（TurnCoordinator/CoordinationStore 契约 + AgentService/InboundMessageService/ChannelAdminService/AgentScheduler 编排改造 + ClusterProperties）、storage（实体/Repository/Jpa 实现 + V6 迁移）、cli（装配 + ClusterStartupCheck + 心跳循环 + Micrometer 指标）、web（/api/v1/instances 端点）、boot（IT）

**Performance Goals**: SC-007——独立会话吞吐双副本 ≥1.8× 单副本；单会话连发延迟与单机排队相当；协调开销 p99 <1%（每轮 ~5 次单行小写：去重 1 + 认领 1 + 续租 ~2 + 释放 1）

**Constraints**: 单机档零配置零行为变化（cluster.enabled=false 全 NOOP）；不重放；回收只认过期租约（绝无全量抹除）；等待有上限；契约面（会话历史/审计/REST/SSE）零改动

**Scale/Scope**: 约 14 个新文件 + 6 个既有文件小改；4 张新表 + 2 列

## Constitution Check

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | ReActLoop 零改动；fencing 两闸在 AgentService 方法体（中断 + 写回前校验），循环无感知 | ✅ |
| II Spring AI 边界 | 无涉 | ✅ |
| III Provider 显式映射 | 无涉 | ✅ |
| IV 目录=Agent / Skill | 无涉（文件面不动；markdown 记忆档以 fail-fast 排除出多副本） | ✅ |
| V 审计 Day One | 强化：崩溃轮次标失败留痕（agent_executions 失败记录）；协调事件结构化日志 + 指标；审计口径零变化 | ✅ |
| VI 安全是地基 | 无新凭证面；日志 sessionId 脱敏 + CRLF sanitize 房规；fail-fast 防带病运行 | ✅ |
| VII 同步 + 虚拟线程 | 认领/等待为同步阻塞轮询；续租器复用既有 ThreadPoolTaskScheduler（与 AgentScheduler 同池形态）；无 Reactor/CompletableFuture | ✅ |
| VIII 状态外置 / Flyway | 本刀即「实例无状态、状态外置」的兑现——协调状态全落共享库；新表走 V6 迁移（025 体系） | ✅ |
| 模块约束 | 契约（TurnCoordinator/CoordinationStore/MessageDeduplicator 接口）进 core，实现进 storage/cli 装配——依赖倒置惯例；零新模块、无循环依赖 | ✅ |

**Phase 1 设计后复评**: 通过。无 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/026-session-ownership/
├── plan.md              # 本文件
├── research.md          # Phase 0：R1~R12
├── data-model.md        # Phase 1：四表两列 + CAS 语义 + 配置面
├── quickstart.md        # Phase 1：V1~V7 验收走查（含压测锚点）
├── contracts/
│   └── coordination.md  # Phase 1：互斥/恰好一次/接管/兼容承诺
└── tasks.md             # Phase 2
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/
│   ├── cluster/TurnCoordinator.java          # 新增：契约（acquire/renew/release + NOOP）——轮次互斥门面
│   ├── cluster/TurnLease.java                # 新增：租约句柄（owner/stillHeld/持有线程登记）
│   ├── cluster/CoordinationStore.java        # 新增：协调存储契约（turn/schedule/channel/receipt/heartbeat 五组 CAS）
│   ├── cluster/ClusterProperties.java        # 新增：oryxos.cluster.*（enabled 默认 false）
│   ├── agent/AgentService.java               # 修改：sessionLocks 内叠加 DB 租约 + 写回前 stillHeld 校验
│   ├── agent/AgentScheduler.java             # 修改：runOnce 到点 CAS 认领（isEnabled 检查后）
│   ├── channel/MessageDeduplicator.java      # 修改：抽为接口（markIfFirst 签名不变）
│   ├── channel/InMemoryMessageDeduplicator.java  # 新增：现状实现平移
│   ├── channel/SharedReceiptDeduplicator.java    # 新增：一级缓存 + 回执表判重
│   └── channel/ChannelAdminService.java      # 修改：startOne 独连型渠道属主判定 + ChannelLeaseCoordinator
├── └── channel/ChannelLeaseCoordinator.java  # 新增：企微连接租约循环（竞争/续租/失去即 stop）

oryxos-storage/
├── src/main/resources/db/migration/sqlite/V6__coordination.sql      # 新增：四表 + scheduled_tasks 两列
├── src/main/resources/db/migration/postgresql/V6__coordination.sql  # 新增：同上 PG 方言
└── src/main/java/io/oryxos/storage/
    ├── TurnLeaseEntity/ChannelEventReceipt/ChannelLeaseEntity/InstanceHeartbeat（实体+Repository，条件 UPDATE @Query）
    └── JpaCoordinationStore.java             # 新增：CoordinationStore 实现（CAS 三式 + CURRENT_TIMESTAMP 基准）

oryxos-cli/src/main/java/io/oryxos/cli/
├── OryxOsRuntime.java                        # 修改：装配（enabled 选 NOOP/Jdbc、去重实现选择、心跳循环、@EnableConfigurationProperties）
├── ClusterStartupCheck.java                  # 新增：误配 fail-fast（SmartInitializingSingleton，端口前失败）
└── MicrometerMetricsRecorder.java            # 修改：+4 租约/去重指标（MetricsRecorder default 方法同步加）

oryxos-web/src/main/java/io/oryxos/web/controller/
└── InstanceApiController.java                # 新增：GET /api/v1/instances（实例+心跳+现役持有）

oryxos-boot/src/test/java/io/oryxos/boot/
├── SessionOwnershipIT.java                   # 新增：双上下文+PG——同会话并发有序恰好一次、重推去重、独立会话并行
├── ScheduleExactlyOnceIT.java                # 新增：双上下文调度恰好一次、执行中 kill 不补发
└── FailoverTakeoverIT.java                   # 新增：kill 持有上下文→轮标失败→健康上下文接管；fencing 写回拒绝
```

**Structure Decision**: 全部编排改造收口在 core 的四个既有类（AgentService/AgentScheduler/ChannelAdminService + 去重接口化），渠道适配器与 ReActLoop 零改动；契约-实现依赖倒置照 015/020 惯例。`processStateless`（群聊）不触碰——无状态路径天然并行。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | 认领收口 AgentService.process | 既有 sessionLocks 位置叠 DB 租约；四入口全覆盖；NOOP 默认单机零变化 |
| R2 | CAS 零方言 | 插入捕约束冲突 + 条件 UPDATE 抢过期；owner=instanceId@epoch；CURRENT_TIMESTAMP 时间基准 |
| R3 | fencing 双闸 | 续租失败→中断持有线程 + saveIfUnchanged 前 stillHeld 硬校验（绝不写回） |
| R4 | 去重两级 | 接口化；进程内一级缓存 + channel_event_receipts 冲突判重；单机档保持内存实现 |
| R5 | 调度到点 CAS | scheduled_tasks 加 claimed_fire_time/claimed_by；fire_time 为 CAS 值；runNow 不认领 |
| R6 | 企微属主 | channel_leases + ChannelAdminService.startOne 收口；飞书/钉钉不走属主 |
| R7 | cluster.* 配置 | enabled 默认 false；instance-id 自动生成；TTL 30s/心跳 TTL/3/轮询 500ms |
| R8 | fail-fast | ClusterStartupCheck（端口前）；顺带修 memory.backend 未知值静默回落缺口 |
| R9 | 心跳+查询 | instances 表 + /api/v1/instances；心跳循环顺手清过期回执 |
| R10 | V6 迁移 | 四表两列纯 SQL 双 vendor；实体归 storage、契约归 core |
| R11 | 指标 | 4 个 default 方法 + oryxos_leases_*/fence_conflicts/duplicates_dropped |
| R12 | 验收载体 | 双上下文 IT 自动化正确性；双真进程压测走查落 SC-007；飞书真机抽查 |
