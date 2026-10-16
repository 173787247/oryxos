# Tasks: 成本看板（Cost Dashboard）

**Feature**: 053-cost-dashboard | **Branch**: `053-cost-dashboard`
**Input**: [spec.md](spec.md) · [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [contracts/cost-api.md](contracts/cost-api.md) · [quickstart.md](quickstart.md)

改动落在 `oryxos-core`(cost 查询契约+聚合) / `oryxos-storage`(JPA 实现) / `oryxos-web`(接口+RBAC+前端)，不新增模块、不建表、不改落账/审计写路径。

## Phase 1: Setup

- [X] T001 确认前端工程 `oryxos-web/src/main/frontend` 可本地构建（`npm ci && npm run build` 产物落 `resources/static/admin`），为后续前端任务就绪（不改代码，验证基线）

## Phase 2: Foundational（阻塞所有故事的查询底座）

- [X] T002 扩展 `oryxos-core/.../cost/CostAttributionQuery.java`：新增可空 `Instant from`、`Instant to`（其余维度不变，向后兼容）
- [X] T003 [P] 新增只读 VO：`oryxos-core/.../cost/DailyCostPoint.java`（date, llmCostMicros）、`ToolUsageEntry.java`（agentName, tool, callCount）、`CostFacets.java`（agents/providers/models/teams）
- [X] T004 扩展 `oryxos-core/.../cost/CostAttributionSummary.java`：新增 `llmCallCount`、`toolCallCount`（只读派生字段，不改落账）
- [X] T005 扩展 `oryxos-core/.../cost/CostLedgerStore.java` 接口：`find/sum*` 支持 `from/to` 时间过滤；新增去重值查询（agent/provider/model/team distinct）
- [X] T006 `oryxos-storage/.../JpaCostLedgerStore.java` + `CostLedgerEntryRepository.java`：实现时间范围过滤与去重查询（`createdAt ∈ [from,to)`；distinct 派生查询），双 vendor(SQLite/PG)均可用
- [X] T007 `oryxos-core/.../cost/CostLedgerService.java`：①query 汇总补 `llmCallCount/toolCallCount`、平均延迟仅 LLM（除零保护）；②新增按天聚合（服务端本地时区分桶、范围内补零）；③新增工具调用归集（sourceKind=TOOL 按 agent/可选工具名计数）；④新增 facets 组装

**Checkpoint**: 查询底座就绪，Web 接口可挂。

## Phase 3: User Story 1 - 总览成本（P1）🎯 MVP

**Goal**: KPI 可读。**Independent Test**: `/cost/attribution` 返回含成本/token/调用数，页面 KPI 卡显示一致。

- [X] T008 [US1] `CostApiController.attribution` 增加可选 `from`/`to` 入参并透传；响应补 `llmCallCount/toolCallCount`（契约 CT-2）
- [X] T009 [P] [US1] `CostApiControllerTest`：attribution 带 from/to 过滤、新增计数字段断言、`cost.enabled=false` 返回 404（CT-1/CT-2）
- [X] T010 [US1] 前端 `features/cost/CostDashboardView.vue` 搭页面骨架 + KPI 卡片（总成本/LLM/工具成本/token/LLM调用数/平均延迟/工具调用数，金额元 2 位，聚合基于微元）
- [X] T011 [US1] `App.vue` 注册 `/cost` 路由与导航「成本」菜单项
- [X] T012 [US1] 前端成本未开启（接口 404）→ 显示「成本功能未开启」空状态（FR-008/SC-005）

**Checkpoint**: MVP——打开成本页能看到 KPI。

## Phase 4: User Story 2 - 按维度下钻（P1）

**Goal**: 维度筛选联动。**Independent Test**: 选 agent 后全页只反映该 agent。

- [X] T013 [US2] 新增 `GET /cost/facets` 接口 + DTO（agent/provider/model/team 去重；run/task 不含）（CT-5）
- [X] T014 [P] [US2] `CostApiControllerTest`：facets 内容与去重、不含 run/task
- [X] T015 [US2] 前端筛选器组件：枚举类（agent/provider/model/team）下拉取自 `/cost/facets`，run/task 文本输入；筛选变更驱动 KPI/趋势/工具/明细联动刷新（FR-003/FR-013）

**Checkpoint**: US1+US2 可用。

## Phase 5: User Story 3 - 成本趋势（P2）

**Goal**: 按天 LLM 成本折线。**Independent Test**: `/cost/daily` 按天补零，页面折线随日期范围/维度联动。

- [X] T016 [US3] 新增 `GET /cost/daily` 接口 + DTO：按天 LLM 成本序列，必填 from/to，跨度 >92 天返回可读参数错误（非 500）（CT-3/FR-004）
- [X] T017 [P] [US3] `CostApiControllerTest`：daily 补零、服务端时区分天、>92 天报错
- [X] T018 [US3] 前端趋势折线（内联 SVG，无图表库）+ 日期范围选择器，默认近 7 天，随筛选联动（FR-004）

**Checkpoint**: 趋势可用。

## Phase 6: User Story 4 - 工具调用分布（P2）

**Goal**: 工具调用按 agent 归集。**Independent Test**: `/cost/tool-usage` 次数之和=范围内 TOOL 条目数。

- [X] T019 [US4] 新增 `GET /cost/tool-usage` 接口 + DTO：按 agent（可选 `byTool` 再按工具名）计数（CT-4/SC-004）
- [X] T020 [P] [US4] `CostApiControllerTest`：tool-usage 按 agent 计数、之和=TOOL 条目数、空态
- [X] T021 [US4] 前端工具调用面板（按 agent 的次数条形/表，可选按工具名细分），随筛选联动，空态处理

**Checkpoint**: 工具视角可用。

## Phase 7: User Story 5 - 流水明细（P2）

**Goal**: 明细表。**Independent Test**: 明细字段齐全、时间倒序、分页。

- [X] T022 [US5] 前端明细表：复用 `/cost/attribution` 的 entries，列含时间/agent/model/token/成本(元2位)/延迟/traceId，默认时间倒序、每页 50、可按时间/成本排序（FR-006）
- [X] T023 [US5] 明细小额成本显示 0.00 时，提供原始精确值查看方式（如悬浮提示，FR-007 精度 Edge Case）

**Checkpoint**: 五个故事齐全。

## Phase 8: Polish & Cross-Cutting

- [X] T024 `oryxos-web/.../security/RequestActionResolver.java`：登记 `/api/v1/cost` 前缀为 READ 动作（VIEWER），覆盖现有 + 新增子路径；修复 RBAC fail-closed 缺口（FR-014/CT-6）
- [~] T025 [P] `RequestActionResolver` 测试 / RBAC 端点覆盖测试：补 `/api/v1/cost/**` 映射断言（VIEWER 可读、匿名 403 非 500）
- [~] T026 按 [quickstart.md](quickstart.md) 跑通 7 个验证场景（起实例 + 造数 curl + 页面手验）
- [~] T027 [P] 改动模块 `mvn verify` 门禁全绿（Spotless/PMD/SpotBugs/测试），既有成本测试不回归；前端构建通过

## Dependencies & Execution Order

```
Phase1(T001) → Phase2 底座(T002→T004→T005→T006→T007; T003[P])
  → US1(T008→T010→T011,T012; T009[P])  [MVP]
  → US2(T013→T015; T014[P])
  → US3(T016→T018; T017[P])
  → US4(T019→T021; T020[P])
  → US5(T022→T023)
  → Polish(T024→T025; T026; T027[P])
```

- Phase 2 阻塞所有故事（查询底座）。各故事的后端接口都依赖 T007 的聚合能力。
- US1 的前端骨架(T010)完成后，US2~US5 的前端块可在其上增量叠加。
- T024(RBAC 登记) 可较早做，但归入 Polish 统一验证；逻辑上独立于故事。

## Parallel Opportunities

- T003（新 VO）与 T002/T004 不同文件，可并行。
- 各故事的契约测试 T009/T014/T017/T020、以及 T025/T027 标 `[P]`，可并行。
- Phase 2 完成后，若多人协作，US1~US5 的后端接口可并行开发（各自独立接口文件），前端按 US1 骨架先行后并入。

## Implementation Strategy

- **MVP = US1**（Phase 1→2→3）：打开成本页看到 KPI 即交付核心价值。
- 增量叠加 US2(筛选)→US3(趋势)→US4(工具)→US5(明细)，每个故事独立可测。
- Polish 统一补 RBAC 登记与全量验收。
