# Data Model: 审计 Trace 串联与脱敏

**Feature**: 021-audit-trace | **Date**: 2026-09-01

**无新表**——Trace 是逻辑实体，由三张既有表中共享同一 `trace_id` 的记录集合构成；变更为三列 ALTER + 派生视图。

## 表变更（AuditSchemaUpgrade 幂等 ALTER + schema.sql 建表段双轨，016/020 同模式）

```sql
ALTER TABLE llm_calls        ADD COLUMN trace_id VARCHAR(64);   -- 可空，旧行为空
ALTER TABLE tool_invocations ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE agent_executions ADD COLUMN trace_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_llm_calls_trace        ON llm_calls (trace_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_trace ON tool_invocations (trace_id);
CREATE INDEX IF NOT EXISTS idx_agent_executions_trace ON agent_executions (trace_id);
```

写入语义：各表既有 fail-open/fail-closed 口径不变；trace_id 由 Jpa 审计实现落库时从 `TraceContext.current()` 读取（可空——理论上兜底 open 后恒有值，防御性可空）。

## 核心上下文（oryxos-core）

### TraceContext（新，`core/agent/`，镜像 ProfileContext 纪律）

| 方法 | 语义 |
|------|------|
| `Scope openIfAbsent()` | 无当前 trace 则生成 UUID、置 ThreadLocal + `MDC.put("traceId")`，返回含 ID 与 owner 标记的 Scope；已有则复用（owner=false） |
| `String current()` | 当前 trace ID，可空 |
| `Scope.close()` | 仅 owner 清 ThreadLocal + MDC（try-with-resources / finally） |

- 生命周期收口：`AgentService.process`/`processStateless` 兜底 open + finally close；REST/SSE controller 先 open 以拿 ID 入响应；`triggerAsync` 主线程生成 → 落执行记录 → **显式传入后台虚拟线程**置入（唯一跨线程点）。
- 隔离：一轮处理 = 一条虚拟线程 = 一个 ThreadLocal 值（宪法 VII 同步模型，SC-003）。

## 查询视图（oryxos-web，派生不落库）

### TraceTimelineView

```
{ traceId, found,
  steps: [ { seq, type: "LLM"|"TOOL", name,            // 模型名 / 工具名
             success, durationMs, at,                   // 完成时刻（created_at）
             promptTokens?, completionTokens?, totalTokens?, costMicros?,   // LLM 步
             inputSummary?, resultSummary?, errorMessage?, blockedBy? } ],  // TOOL 步（截断+脱敏后）
  summary: { steps, llmCalls, toolCalls, totalTokens, costMicros, totalDurationMs } }
```

### 既有视图增列（JSON 只增字段，兼容）

- `LlmCallView` + `traceId`；`ToolInvocationView` + `traceId`；`AgentExecutionView` + `traceId`；`MessageResponse` + `traceId`。
- SSE：新增 `trace` 事件（`{"traceId":…}`，流建立即发）；`done` 负载加 `traceId` 字段——均遵循 019「只增不改」。

## 脱敏（oryxos-web `audit/Redactor`，静态规则内置）

| 形态 | 匹配 | 掩码 |
|------|------|------|
| API key 已知前缀 | `sk-…`、`oryx_…` 等前缀 + 长随机串 | 前 4 字符 + `****` |
| Authorization | `Authorization[":= ]+<scheme> <credentials>` | 凭证段掩码 |
| URL userinfo | `scheme://user:pass@host` | `user:****@` |
| 敏感字段值 | `password/passwd/secret/token/api_key/apikey/access_key` 的 JSON/键值对取值 | 值掩码 |

- 应用点：TraceTimelineView 的 inputSummary/resultSummary/errorMessage（先截断 200 字符再脱敏）；未来任何内容展示面统一调用（FR-008）。
- 不落库、不可配置（Clarifications 裁决）；`llm_calls` 无内容字段、无脱敏对象（事实性修正）。

## 不新增的

- 无新表、无 Trace 实体表；旧行 trace_id 为空不参与按 trace 查询（如实为空）；审计契约（两 Auditor 接口）零改动。
