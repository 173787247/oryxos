# Contract: 成本看板查询接口（/api/v1/cost）

**Feature**: 053-cost-dashboard

全部为只读 GET，统一 `ApiResponse` 包装；`oryxos.cost.enabled=false` 时一律 404（前端据此显示空状态）。RBAC 开启时最低角色 VIEWER（见 FR-014，需在 `RequestActionResolver` 为 `/api/v1/cost` 前缀登记读动作）。金额字段一律微元（long），前端换算为元 2 位展示。

## GET /api/v1/cost/attribution（扩展既有）

既有维度参数 + 新增可选 `from`/`to`（ISO-8601 Instant）。响应在既有汇总基础上新增 `llmCallCount`、`toolCallCount`。

```
GET /api/v1/cost/attribution?agent=ops&from=2026-10-01T00:00:00Z&to=2026-10-08T00:00:00Z
```
```json
{"code":0,"message":"success","data":{
  "totalCostMicros":123456,"llmCostMicros":123456,"toolCostMicros":0,
  "promptTokens":1000,"completionTokens":500,"totalLatencyMs":8200,
  "llmCallCount":42,"toolCallCount":7,
  "priceVersions":[1],"entries":[ ... ]}}
```

## GET /api/v1/cost/daily（新增）

按天 LLM 成本序列，支撑趋势折线。参数：维度过滤 + `from`/`to`（必填，跨度 ≤ 92 天）。

```json
{"code":0,"message":"success","data":[
  {"date":"2026-10-01","llmCostMicros":45000},
  {"date":"2026-10-02","llmCostMicros":0}
]}
```
- 范围内无成本的日期 MUST 补 `llmCostMicros:0`（不断点）。
- 跨度 > 92 天 MUST 返回可读的参数错误（非 500）。

## GET /api/v1/cost/tool-usage（新增）

按 agent（可选 `byTool=true` 再按工具名）的工具调用次数。参数：维度过滤 + 可选 `from`/`to`。

```json
{"code":0,"message":"success","data":[
  {"agentName":"ops","tool":null,"callCount":12},
  {"agentName":"research","tool":null,"callCount":5}
]}
```
- 计数口径 = `sourceKind=TOOL` 条目数；各项之和 = 范围内工具条目总数（SC-004）。

## GET /api/v1/cost/facets（新增）

筛选器下拉候选值（去重、稳定排序）。run/task 不含（文本输入）。

```json
{"code":0,"message":"success","data":{
  "agents":["ops","research"],"providers":["deepseek","qwen"],
  "models":["deepseek-chat"],"teams":["team-a"]}}
```

## 契约规则

| 规则 | 说明 | 来源 |
|---|---|---|
| CT-1 | 所有 cost 接口在未开启时 404，前端转空状态 | FR-008 |
| CT-2 | attribution 新增 `llmCallCount`/`toolCallCount`；旧字段不变（向后兼容） | FR-002 / 数据模型 |
| CT-3 | daily 每日补零、跨度 ≤92 天、服务端时区分天 | FR-004 |
| CT-4 | tool-usage 口径为 TOOL 条目计数，可按 agent/工具名归集 | FR-005 |
| CT-5 | facets 仅 agent/provider/model/team，run/task 不含 | FR-013 |
| CT-6 | RBAC 开启时 `/api/v1/cost/**` 为 VIEWER 读动作，已在 resolver 登记 | FR-014 |
| CT-7 | 金额为微元 long，聚合基于微元；展示换算与取整在前端 | FR-007 |
