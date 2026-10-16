# Phase 1 Data Model: 成本看板

**Feature**: 053-cost-dashboard | **Date**: 2026-10-14

本特性**不新增持久化表**，仅扩展查询契约与只读视图（数据源为既有 `cost_ledger_entries`）。

## 扩展：CostAttributionQuery（core）

既有 6 维度 + 新增时间范围（均可空）：

| 字段 | 类型 | 说明 |
|---|---|---|
| runId/taskId/agentName/teamId/provider/model | String | 既有维度过滤（可空=不限） |
| `from` | Instant（新增，可空） | 过滤 `createdAt >= from`；null=不限下界 |
| `to` | Instant（新增，可空） | 过滤 `createdAt < to`；null=不限上界 |

## 扩展：CostAttributionSummary（core）

既有字段不动，新增只读计数（派生自 entries 的 sourceKind）：

| 字段 | 类型 | 说明 |
|---|---|---|
| llmCostMicros/toolCostMicros/totalCostMicros | long | 既有 |
| totalLatencyMs/promptTokens/completionTokens/priceVersions/entries | — | 既有 |
| `llmCallCount` | long（新增） | `sourceKind=LLM` 条目数 |
| `toolCallCount` | long（新增） | `sourceKind=TOOL` 条目数 |

> 平均延迟（仅 LLM）= 总 LLM 延迟 / `llmCallCount`（`llmCallCount=0` 时为 0，避免除零）。

## 新值对象（core，只读查询结果）

- **DailyCostPoint**：`date`(服务端本地日)、`llmCostMicros`(当日 LLM 成本)。趋势序列 = `List<DailyCostPoint>`，范围内无数据的日期补零。
- **ToolUsageEntry**：`agentName`、`tool`(可空，按工具名细分时填 `sourceRef`)、`callCount`。工具调用归集 = `List<ToolUsageEntry>`。
- **CostFacets**：`agents`/`providers`/`models`/`teams` 四个去重字符串列表（排序稳定）。run/task 不入 facets。

## 展示换算（前端）

- 元 = 微元 ÷ 1,000,000，统一 2 位小数展示。
- 聚合（KPI/趋势/占比）基于原始微元求和后再格式化，MUST NOT 先逐条取整（FR-007）。

## 边界与规则

- 日期范围跨度 MUST ≤ 92 天（FR-004）；越界由接口参数校验拒绝并返回可读错误。
- 所有查询在 `oryxos.cost.enabled=false` 时 404（沿用既有门控）。
- 新增字段/值对象均只读派生，不参与 `recordLlm/recordTool` 落账路径（FR-010）。
