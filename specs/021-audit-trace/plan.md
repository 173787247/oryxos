# Implementation Plan: 审计 Trace 串联与脱敏

**Branch**: `021-audit-trace` | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/021-audit-trace/spec.md`

## Summary

单轮处理全链路可回放：`TraceContext`（ThreadLocal + MDC，镜像 ProfileContext 纪律）在 `AgentService` 入口兜底生成、REST/SSE controller 先开以回传；审计三表各加 `trace_id` 列（AuditSchemaUpgrade + schema.sql 双轨），**审计契约零改动**——Jpa 实现落库时自读 TraceContext（019/020 交互契约教训的制度化）；`GET /api/v1/audit/trace/{id}` 合并双表为时间线 + 汇总；回传三通道（MessageResponse 字段 / SSE `trace` 事件 / 执行历史）；`Redactor` 单类做展示层脱敏（落库原文，Clarifications 裁决）；日志面**零配置改动**——logback 已预埋 `traceId` MDC 位（摸底发现），只需放值。零新依赖、零新表、零新模块。

## Technical Context

**Language/Version**: Java 21（虚拟线程——一轮处理一条线程是 ThreadLocal 隔离的前提）

**Primary Dependencies**: Spring Boot 3.x、SLF4J MDC（既有）、Vue 3（报表页时间线）；零新增依赖

**Storage**: 三表各加 `trace_id VARCHAR(64)` 可空列 + 索引（幂等 ALTER + schema.sql 双轨，无新表）

**Testing**: JUnit 5——core（TraceContext 生命周期/嵌套/MDC 同步）、storage（落列/findByTraceId/升级幂等）、web（时间线合并排序/汇总/脱敏规则/回传字段）、boot E2E（三通道回传 + 全链路 + 并发隔离 + 脱敏落库对照）；`mvn verify` 全量门禁

**Target Platform**: Linux server（单 fat JAR，同现状）

**Project Type**: Maven 多模块单体——oryxos-core（TraceContext + AgentService 收口）、oryxos-storage（列/索引/实现读上下文）、oryxos-web（时间线 API/Redactor/回传/前端）、oryxos-cli（triggerAsync 跨线程传递点）

**Performance Goals**: trace 点查走索引；时间线合并为两次索引查询 + 内存排序（单轮步数十以内）

**Constraints**: 审计契约零改动（Auditor 接口不动）；JSON/事件/列全部纯增量（回归零破坏）；MDC 必须 finally 清理（虚拟线程复用防串号）；`triggerAsync` 后台线程是唯一跨线程传递点（显式传 ID）

**Scale/Scope**: 约 5 个新文件 + 12 个既有文件小改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | ReActLoop 零改动；trace 是环境上下文，循环无感知 | ✅ |
| II Spring AI 边界 | 不涉及 | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 目录=Agent / Skill | 不涉及 | ✅ |
| V 审计 Day One | 强化而非改变：trace 列使审计从「可查」升级为「可回放」；写入口径不变 | ✅ |
| VI 安全是地基 | 脱敏为展示防线（落库原文的安全边界=库访问运维特权 + 018 门禁，Clarifications 裁决落卷）；Redactor 规则内置防注入面 | ✅ |
| VII 同步 + 虚拟线程 | ThreadLocal 隔离正建立在同步模型上；无异步引入；跨线程仅 triggerAsync 一处显式传递 | ✅ |
| VIII 状态外置 / 手工 schema | 三列 ALTER 走 AuditSchemaUpgrade 幂等模式 + schema.sql 双轨；TraceContext 是请求生命周期上下文非状态 | ✅ |
| 模块约束 | TraceContext 进 core（消费方在 core/storage/web）；不新建模块，无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/021-audit-trace/
├── plan.md              # 本文件
├── research.md          # Phase 0：8 项技术裁决（R1~R8）
├── data-model.md        # Phase 1：三列 ALTER + TraceContext + 时间线视图 + 脱敏规则
├── quickstart.md        # Phase 1：V1~V8 验收走查
├── contracts/
│   └── trace-api.md     # Phase 1：trace 语义承诺 + 回传通道 + 时间线 API + 脱敏承诺
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/agent/
│   ├── TraceContext.java            # 新增：ThreadLocal + MDC 同步（openIfAbsent/current/Scope.close）
│   └── AgentService.java           # 修改：process/processStateless 兜底 open + finally close
└── src/test/java/io/oryxos/core/agent/
    └── TraceContextTest.java        # 新增：生成/复用/嵌套不覆盖/close 只清 owner/MDC 同步

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── LlmCall.java / ToolInvocation.java / AgentExecutionEntity.java  # 修改：traceId 字段
│   ├── JpaLlmCallAuditor.java / JpaToolInvocationAuditor.java          # 修改：落库读 TraceContext.current()
│   ├── LlmCallRepository.java / ToolInvocationRepository.java          # 修改：findByTraceId
│   └── AuditSchemaUpgrade.java                                          # 修改：三列 ALTER + trace 索引
├── src/main/resources/schema.sql                                        # 修改：建表段 + 索引双轨
└── src/test/java/io/oryxos/storage/AuditSchemaUpgradeTest.java          # 修改：追加断言

oryxos-web/
├── src/main/java/io/oryxos/web/
│   ├── audit/Redactor.java              # 新增：展示层脱敏单类（截断+掩码）
│   ├── audit/AuditMetricsService.java   # 修改：traceTimeline(id) 合并双表 + 汇总
│   ├── controller/AuditApiController.java   # 修改：GET /audit/trace/{id}
│   ├── controller/dto/…View.java        # 修改：LlmCallView/ToolInvocationView/AgentExecutionView/MessageResponse 加 traceId
│   └── sse/SseStreamSupport.java        # 修改：流建立发 trace 事件；done 带 traceId
├── src/main/frontend/src/App.vue        # 修改：报表页 trace 查询 + 时间线视图；明细行 traceId
└── src/test/java/io/oryxos/web/
    ├── audit/RedactorTest.java          # 新增：四类形态掩码 + 不误伤
    └── controller/TraceTimelineTest.java # 新增：合并排序/汇总/found=false/脱敏应用

oryxos-cli/
└── src/main/java/io/oryxos/cli/…        # 修改：triggerAsync 主线程生成 trace → 落执行记录 → 传后台线程

oryxos-boot/
└── src/test/java/io/oryxos/boot/TraceE2ETest.java  # 新增：三通道回传 + 全链路 + 并发隔离 + 脱敏落库对照
```

**Structure Decision**: `TraceContext` 进 core（AgentService 收口 + storage 读取 + web 回传三方消费）；脱敏在 web（当前唯一展示面）；审计契约零改动是本次的架构红线——上下文横切靠环境读取而非参数传递，019/020 两轮契约保全教训的制度化。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | TraceContext ThreadLocal+MDC | AgentService 兜底 open/finally close；logback 已预埋 traceId 位，日志配置零改动 |
| R2 | 审计契约零改动 | Jpa 实现落库自读 TraceContext；既有 stub/verify 全保全 |
| R3 | 三列 ALTER 双轨 | 016/020 同模式 + trace 索引；agent_executions 也加列（执行历史载体） |
| R4 | 回传三通道纯增量 | MessageResponse 字段 / SSE `trace` 事件+done 字段 / 执行历史视图；triggerAsync 是唯一跨线程显式传递点 |
| R5 | 时间线合并查询 | 两次索引查 + created_at 排序；空结果 found=false 不 404 |
| R6 | Redactor 单类 | 四类内置形态、前4+****；截断后脱敏；落库原文（Clarifications） |
| R7 | 报表页时间线 | trace 输入框 + 步骤视图；明细行 traceId 可点查 |
| R8 | 不做的 | OpenTelemetry（v0.4）、日志查询 API、trace 配置项 |
