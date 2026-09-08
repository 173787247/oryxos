# Roadmap 备忘：Agent 执行链路可观测（langfuse 式 trace）

> 来源：016 审计看板特性 clarify 阶段提出的需求，拍板「另立独立特性」，不在 016 范围内。
>
> 记录日期：2026-08-21

## 目标

让用户能查看 Agent 执行链路的可观测数据，对标 **langfuse / langsmith** 这类 LLM 可观测平台——可观测、可评估、自进化。

核心能力（初步设想）：

1. **Trace/span 级执行链路可视化**：每次 LLM 调用、每个工具步骤的嵌套调用树（谁调了谁、入参、结果、耗时）
2. **评估（evaluation）**：对执行结果做质量评估、评分
3. **自进化**：基于观测数据优化 Agent（prompt、工具组合、执行策略）

## 与现有基础的关系

| 基础 | 现状 | 缺口 |
|------|------|------|
| 审计表 `llm_calls`/`tool_invocations` | Day One 已有完整原始数据（provider/model/token/耗时/成败） | 两张表**无 `llm_call_id` 关联**，拼不出 trace 树 |
| [AuditTraceLink.md](AuditTraceLink.md) 方案 | 已设计「工具调用关联到 LLM 调用」的 `llm_call_id` 传递链路 | 尚未实现 |
| 016 审计看板 | 成本/调用量/成功率的聚合看板 | 只到聚合，不到 trace 级 |

## 关键前置

- **`llm_call_id` 关联**（[AuditTraceLink.md](AuditTraceLink.md)）：让 `tool_invocations` 知道自己由哪次 `llm_call` 触发，是拼 trace 树的硬前提。
- 可能还需：`ProviderResponse` 携带 `llmCallId`、`ToolExecutor.execute` 透传、`LlmCallAuditor.record` 返回 id。

## 模块建议

**建议新建 `oryxos-observability` 模块**（独立能力域），而非塞进既有模块：

- trace/span 数据模型、链路聚合、可视化 API、评估框架是一整套独立能力
- 与 `oryxos-core`（契约）、`oryxos-storage`（持久化）、`oryxos-web`（展示）的边界清晰
- 按宪法 VIII「新建模块声明理由 + 同步 CLAUDE.md 模块表 + TechnicalSolution §10」流程立项

## 时机

016 审计看板完成后，作为独立特性立项（走 spec-kit 流程）。016 的 `profile_name`/成本列 + 未来的 `llm_call_id` 是它的数据地基。
