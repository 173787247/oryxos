# Research: 审计 Trace 串联与脱敏

**Feature**: 021-audit-trace | **Date**: 2026-09-01

技术上下文无 NEEDS CLARIFICATION；本文记录关键技术裁决，全部基于实地摸底（`ProfileContext`/`ToolExecutionContext` ThreadLocal 先例、logback-spring.xml 的 traceId MDC 预埋位、审计三表与 Jpa 审计实现、`AgentService`/`triggerAsync` 入口、019 SSE 编排、016 审计查询面）。

## R1. Trace 传播：TraceContext（ThreadLocal + MDC 同步，core）

**Decision**: oryxos-core 新增 `agent/TraceContext`：`String openIfAbsent()`（无则生成 UUID、置入 ThreadLocal 并 `MDC.put("traceId", …)`，返回当前 ID 与「是否本层开启」）、`String current()`、`void close(...)`（仅本层开启者清 ThreadLocal + MDC）。`AgentService.process`/`processStateless` 入口兜底 open、finally close（镜像其 ProfileContext 生命周期收口模式）——覆盖 CLI/定时/飞书全部触发源；REST/SSE controller 在调用前先 open 以便把 ID 写进响应（AgentService 检测已开启则沿用）。

**Rationale**: 同步阻塞 + 虚拟线程下一轮处理整体在一条线程（宪法 VII），ThreadLocal 即天然隔离（SC-003）；MDC 同步一次置入，日志面零散点改动为零——**logback 配置已预埋 `%X{traceId:-}` 与 prod JSON 的 `includeMdcKeyName traceId`（摸底发现），配置零改动**。ProfileContext 的「入口 set、finally 必清」是既有纪律，TraceContext 同型。

**Alternatives considered**: 显式参数逐层传递（否——审计写入点分散在 provider/executor/storage，逐层加参波及全部契约与既有测试）；仅 MDC 不设 ThreadLocal（否——审计落库方需要类型安全的读取点，MDC 语义上属日志）。

## R2. 审计落列：storage 实现内读 TraceContext，审计契约零改动

**Decision**: `LlmCallAuditor`/`ToolInvocationAuditor` 接口**不改**；`JpaLlmCallAuditor`/`JpaToolInvocationAuditor` 落库时自行 `TraceContext.current()` 填 `trace_id` 列（storage 依赖 core，合法）。写入 fail-open/fail-closed 口径不变（FR-002）。

**Rationale**: 019/020 两轮的教训——审计契约每动一次就要保全既有 stub/verify；trace 是横切上下文，从环境取而非从参数传，契约与全部既有测试零波及。

## R3. schema：三表各加 `trace_id` 列 + 索引（016/020 同模式双轨）

**Decision**: `llm_calls`、`tool_invocations`、`agent_executions` 各加 `trace_id VARCHAR(64)` 可空列——`AuditSchemaUpgrade` 幂等 ALTER（存量库）+ schema.sql 建表段同步（新装库），并建 `idx_*_trace` 索引；repos 加 `findByTraceId`。`agent_executions` 加列是 FR-005「执行历史展示 trace ID」的载体。

**Rationale**: 与 016 cost_micros/020 blocked_by 完全同模式（宪法 VIII 手工 schema）；trace 查询是点查，索引必要。

## R4. 回传三通道

**Decision**:
- REST：`MessageResponse` record 加 `traceId` 组件（JSON 增字段，旧消费方兼容）——controller open 后把 ID 装入响应；
- SSE：流建立后立即发新事件类型 `trace`（`data: {"traceId":"…"}`，019 承诺「类型只增不改」旧客户端安全忽略）；`done` 元数据同带 `traceId` 字段（断线重连兜底）；
- 执行历史：`triggerAsync` 在主线程生成 trace ID → 落 `agent_executions.trace_id` → 传入后台虚拟线程 `TraceContext` 置入后再跑（后台线程与主线程不同线程，必须显式传递——SC-003 的唯一跨线程点）；`AgentExecutionView` 加 `traceId` 字段。

## R5. 时间线查询 API

**Decision**: `GET /api/v1/audit/trace/{traceId}`（挂 `AuditApiController`）：两 repo `findByTraceId` → 合并按 `created_at` 排序为 steps（type=LLM/TOOL、name、success、durationMs、startedAt；LLM 步带 tokens/costMicros；TOOL 步带 inputSummary/resultSummary/errorMessage/blockedBy）+ summary（totalDurationMs=末步结束−首步开始、totalTokens、costMicros 合计、步数分项）。未命中返回空 steps + `found:false`。既有列表视图 `LlmCallView`/`ToolInvocationView` 加 `traceId` 字段（016 列表的 trace 维度入口）。

**Rationale**: 时间序用 created_at（审计写入即步骤完成时刻，同轮内单调）；空结果如实返回不 404（spec Edge「无审计记录的 trace」）。

## R6. 脱敏：`Redactor` 单类（web/audit），展示层统一出口

**Decision**: oryxos-web 新增 `audit/Redactor`（静态工具类）：内置正则集合——常见 API key 形态（`sk-…`/`oryx_…` 等长随机串带已知前缀）、`Authorization: <scheme> <credentials>`、URL userinfo 段（`scheme://user:pass@`）、JSON/表单风格敏感字段名（`password|passwd|secret|token|api_key|apikey|access_key` 的值）——命中值替换为 `前4字符+****`；不含敏感形态原样返回（FR-007 不误伤）。时间线的 inputSummary/resultSummary/errorMessage 先截断（默认 200 字符）后脱敏，所有展示面（本 feature 的 timeline 与未来任何内容展示）统一经此出口（FR-008）。规则内置、不可运行时配置（Clarifications 裁决）。

**Rationale**: 落库原文 + 展示层掩码（Clarifications Q1）；当前唯一展示内容的面是新时间线（016 列表不展参数、020 页面不展参数——摸底确认），单类即满足「单一共享实现」，未来面接入只需调用同一方法。

## R7. 管理台：报表页加 trace 查询入口 + 时间线视图

**Decision**: App.vue 报表（report）页顶部加 trace ID 输入框；查询后渲染时间线（步骤序号/类型徽标/名称/成败/耗时，LLM 步显示 token 与成本、TOOL 步显示脱敏后的参数/结果摘要，blocked_by 标记可见）；LLM/工具明细列表行显示 traceId 并可点击填入查询框（016 列表的 trace 维度）。执行历史列表行展示 traceId。

## R8. 覆盖面与不做的

**Decision**: TraceContext 兜底 open 覆盖 REST/SSE/CLI/定时/飞书全部入口（后两者无回传需求，spec FR-005 只承诺三通道）；不做 OpenTelemetry/跨进程传播（v0.4）、不做日志查询 API（logback 已带 traceId 字段，检索靠部署方日志设施）。
