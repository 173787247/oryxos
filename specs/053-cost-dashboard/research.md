# Phase 0 Research: 成本看板

**Feature**: 053-cost-dashboard | **Date**: 2026-10-14

勘察现有成本后端（`oryxos-core/cost`、`oryxos-storage`）、Web 层（`CostApiController`、`RequestActionResolver`）与管理台前端（`oryxos-web/src/main/frontend`）后，解决规格留下的实现未知数。

## 现状基线

- 查询契约 `CostAttributionQuery(runId, taskId, agentName, teamId, provider, model)`——**无时间维度**。
- `CostLedgerService.query()` 返回 `CostAttributionSummary`（含 llm/tool 成本、总延迟、token、entries）——**无 LLM/工具调用次数拆分**。
- `CostLedgerStore`：`find(query)` / `sumLlmCostMicros` / `sumToolCostMicros`——无时间过滤、无按天聚合、无去重值。
- 账本每条 entry 含 `sourceKind`(LLM/TOOL)、`sourceRef`(工具条目=工具名)、`agentName`、`latencyMs`、`createdAt`、`*CostMicros`——支撑工具调用归集与按天聚合。
- `CostApiController` 在 `oryxos.cost.enabled=false` 时所有路由 404。
- **RBAC**：`RequestActionResolver` 无 `/api/v1/cost` 分支（仅 `/api/v1/pricing` 等有）→ RBAC 开启时现有 cost 接口即 fail-closed 403。
- 前端：`oryxos-web/src/main/frontend` 为 Vue SPA，`vite base=/admin/`、产物落 `resources/static/admin`；路由与导航集中在 `App.vue`；**无图表库依赖**。

## Decision 1：时间过滤加在 Query，按天聚合放 Service 层

- **Decision**: `CostAttributionQuery` 增加可空 `Instant from / Instant to`；`CostLedgerStore.find/sum*` 实现按 `createdAt ∈ [from,to)` 过滤。按天聚合在 `CostLedgerService` 对过滤后的 entries 按**服务端本地时区**分桶求和（不下推到 SQL）。
- **Rationale**: 加时间参数向后兼容（旧调用传 null 即全量）。按天聚合放 service 层实现简单、不写复杂 SQL；92 天上限 + 企业常规数据量下，读 entries 再分桶的开销可接受（宪法 VII 同步模型，虚拟线程扛 IO）。
- **Alternatives**: SQL `GROUP BY date` 下推——性能更好但要双 vendor SQL + 时区处理复杂，首版不值得；留作数据量增长后的优化。

## Decision 2：Summary 增补调用次数字段（加列不改写路径）

- **Decision**: `CostAttributionSummary` 增补 `llmCallCount`、`toolCallCount`（按 `sourceKind` 计数），平均延迟 = 总 LLM 延迟 / `llmCallCount`。均为**只读派生**，不改落账写路径（满足 FR-010）。
- **Rationale**: KPI 需要 LLM 调用数、工具调用数、平均延迟（仅 LLM）。在聚合时顺带计数，避免前端靠分页 entries 估算。
- **Alternatives**: 前端从 entries 数——entries 可能被分页截断，计数不准，否决。

## Decision 3：新增 3 个只读查询接口 + 扩展 attribution

- **Decision**: 在 `/api/v1/cost` 下：
  - `GET /attribution` 增加可选 `from`/`to`（扩展既有，兼容）。
  - `GET /daily`：按天的 LLM 成本序列（`[{date, llmCostMicros}]`），支撑趋势折线。
  - `GET /tool-usage`：按 agent（可选再按 `tool`）的工具调用次数（`sourceKind=TOOL` 计数）。
  - `GET /facets`：agent/provider/model/team 的去重可选值，供筛选器下拉。
- **Rationale**: 对齐 FR-004（趋势）、FR-005（工具调用归集）、FR-013（筛选器候选值）；run/task 为高基数 ID，不进 facets（文本输入）。各接口只读、受 `oryxos.cost.enabled` 门控（关则 404）。
- **Alternatives**: 把 daily/tool-usage 塞进 attribution 一个大响应——职责混杂、缓存粒度差，分开更清晰。

## Decision 4：RBAC 补登记 /api/v1/cost 为读动作（VIEWER）

- **Decision**: 在 `RequestActionResolver` 为 `/api/v1/cost`（前缀，覆盖现有 + 新增子路径）登记为读动作，角色档 VIEWER；与 `/api/v1/pricing`/providers 的读映射同风格。
- **Rationale**: 修复现有 fail-closed 缺口并满足 FR-014；前缀登记让 daily/tool-usage/facets 自动纳入，避免逐个漏登记。
- **Alternatives**: 逐路径登记——易漏，前缀更稳。

## Decision 5：前端加 features/cost 页 + 轻量 SVG 图，无新依赖

- **Decision**: 新增 `features/cost/CostDashboardView.vue`，在 `App.vue` 注册路由与导航「成本」项；折线/占比用**内联 SVG**手绘（不引入 echarts 等重依赖，贴合管理台既有风格与 `oryxos-admin-ui` 的无重依赖约定）；金额 2 位格式化、聚合基于微元；复用既有 API 客户端；捕获 cost 404 → 「成本功能未开启」空状态。
- **Rationale**: 保持前端零新增重依赖、构建产物小；SVG 折线对"单条每日成本线"足够。
- **Alternatives**: 引 echarts/apexcharts——功能强但体积大、与现有无图表库现状不符，首版不引。

## 对宪法的影响

- V 审计：只读查询，不新增 tool/LLM 调用、不写审计表——N/A。
- VI 安全：不走 Shell/文件白名单；新增接口纳入 RBAC 读动作（VIEWER）。
- VII 同步：全同步查询，无 Reactor/异步。
- X 只读/不改写路径：仅加查询与派生字段，不触碰 `recordLlm/recordTool` 落账与审计表（FR-010）。
- 模块：改动落在 `oryxos-core`(cost 查询/summary)、`oryxos-storage`(Jpa 实现)、`oryxos-web`(接口+RBAC+前端)，不新增模块、不改跨模块契约方向（契约仍在 core，storage 实现）。

**所有 NEEDS CLARIFICATION 已解决，进入 Phase 1。**
