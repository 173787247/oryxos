# Tasks: 审计查询接口与报表看板

**Input**: Design documents from `specs/016-audit-dashboard/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/rest-api.md、quickstart.md

**Tests**: 项目宪法要求质量门禁，采用测试先行（harness 先行）——每个故事先写失败测试再实现。

**Organization**: 按用户故事分组，每个故事独立可测、可增量交付。

## 约定

- **[P]** 可并行（不同文件、无未完成依赖）
- **[USn]** 归属用户故事；迁移/契约/收尾无故事标签
- 审计写入链路的 fail-open/fail-closed 语义不变，新列可空

## 验收 Harness（实现门禁）

> implement 期间逐任务执行；**能机器判的绝不留给人，机器判不了的绝不自行发挥**。

### 硬门禁（不过不放行）

- **H1** `mvn clean verify` 全绿（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + 全部测试）。任一失败即阻断，不得宣称完成。
- **H2** 6 条 SC 对应的测试全部落地且通过：SC-001→`CostComputeTest`、SC-002→`AuditMetricsServiceTest`、SC-003→改价回归、SC-004→按 Agent 归属、SC-005→`AuditSchemaUpgradeTest`、SC-006→人工（quickstart S4）。

### 软门禁（立即停下、报告、等确认）

- **S1** 需创建任务清单之外的任何对外概念（新表/新列/新端点/DTO 之外）。
- **S2** 需修改任何已定字面量（类名、方法签名、表列名、端点路径）。
- **S3** 需修改前序特性（014/015 等）的公共契约；或本特性审计写入链路的既有 fail-open/fail-closed 语义。

### 反作弊

- **A1** 不得删断言、加 `@Disabled`、放宽阈值让测试变绿。实现错修实现；认为测试错，停下报告，不擅自改测试。

### H4 全局不变量自查（实现完成后逐条核对）

- **G1** 审计完整性：LLM 调用**成败都**落 `llm_calls`（含 `profileName`/`costMicros`），工具调用**成败都**落 `tool_invocations`（含 `profileName`）——本特性的地基是审计，绝不能因加成本/归属而破坏审计写入。
- **G2** 成本可空语义：失败调用/未配置模型定价 → `costMicros = null`（未计量），绝不误记 0；聚合 SUM 忽略 null。
- **G3** 无明文 key：api-key 仍走掩码（价格非敏感，但沿用既有凭证处理）。
- **G4** 同步模型：成本计算、聚合查询、迁移均无 Reactor / `CompletableFuture` / 自建线程池。
- **G5** 无 Spring AI 自动工具执行路径（本特性只加成本计算，不碰 LLM 调用方式）。

### 六项证据 DoD（全部满足才宣布完成）

1. `mvn clean verify` 全绿（贴关键输出）；
2. 每个测试类存在且非空（`CostComputeTest`/`AuditMetricsServiceTest`/`AuditSchemaUpgradeTest`/`PricingApiControllerTest` 等逐个对号）；
3. 交付物逐项 ls/grep 存在性核对（`llm_pricing` 表、`LlmPricing` 实体、`PricingApiController`、`AuditApiController`、`AuditSchemaUpgrade`、App.vue 报表页）；
4. 前序特性（014/015）测试回归绿；
5. H4 六条不变量（G1~G5）逐条自查通过；
6. 验收报告：以上证据 + quickstart 剩余人工项清单（真实模型/真实 provider 配价/看板手点）。

---

## Phase 1: Foundational（迁移 + 契约 + 审计写入）

**Purpose**: 加 5 列 + 契约签名 + 审计写入新列，是所有故事的地基。

**⚠️ 阻塞**: 未完成前任何用户故事不得开始。

- [x] T001 [P] 更新 `oryxos-storage/src/main/resources/schema.sql`：`llm_calls` 加 `cost_micros`/`profile_name`，`tool_invocations` 加 `profile_name`，新增 `llm_pricing` 表（`UNIQUE(provider, model)`），加 `idx_llm_calls_profile`/`idx_tool_invocations_profile` 索引（`CREATE INDEX IF NOT EXISTS`）
- [x] T002 [P] 新增 `oryxos-storage/src/main/java/io/oryxos/storage/AuditSchemaUpgrade.java`：启动时检查列/表是否存在、缺则 `ALTER TABLE ADD COLUMN` + `CREATE TABLE IF NOT EXISTS llm_pricing`（幂等，参考 `ScheduleSchemaUpgrade`）
- [x] T003 [P] 新增 `oryxos-storage/.../storage/LlmPricing.java` 实体（provider/model/promptPrice/completionPrice + 时间戳，`UNIQUE(provider, model)`）
- [x] T004 [P] `LlmCall.java` 加 `costMicros`/`profileName` 字段 + getter/setter
- [x] T005 [P] `ToolInvocation.java` 加 `profileName` 字段 + getter/setter
- [x] T006 [P] 新增 `oryxos-core/.../provider/ModelPricing.java` 跨模块值对象（provider/model/promptPrice/completionPrice）
- [x] T007 `oryxos-core/.../provider/LlmCallAuditor.java` `record(...)` 加 `String profileName, Long costMicros` 参数
- [x] T008 `oryxos-core/.../agent/ToolInvocationAuditor.java` `record(...)` 加 `String profileName` 参数
- [x] T009 `JpaLlmCallAuditor.java` 写 `profileName`/`costMicros` 到实体
- [x] T010 `JpaToolInvocationAuditor.java` 写 `profileName` 到实体
- [x] T011 [P] 新增 `LlmPricingRepository.java`：`findByProviderAndModel`、`findAll`、`deleteById`
- [x] T012 [P] 新增 `AuditSchemaUpgradeTest.java`：存量库（无新列/表）启动补列+建表幂等、历史行新列为 null

**Checkpoint**: 迁移 + 契约 + 审计写入就绪。

---

## Phase 2: User Story 3 - 成本计算与写时定格落库（Priority: P1）

**Goal**: 为具体模型配单价（管理台）、LLM 调用写时定格成本（微元）、历史成本不随价格变。

**Independent Test**: 配单价后触发 LLM 调用，断言 `llm_calls.cost_micros` 正确；改价后历史记录成本不变。

### Tests for US3

- [x] T013 [US3] 新增 `oryxos-provider/src/test/java/io/oryxos/provider/CostComputeTest.java`：`1元/百万×1000 token == 1000 微元`、舍入边界、null 分支（usage null / 未定价）——先写、确认失败

### Implementation for US3

- [x] T014 [US3] `SpringAiProviderServiceImpl.java`：加 `computeCost(ModelPricing, Usage)` 私有方法，按 `(provider, model)` 从 `LlmPricingRepository` 查价；成功/失败两处 `audit.record` 传 `profile.name()` 与 `costMicros`（查不到价 → null）
- [x] T015 [US3] `ToolExecutor.java`：`execute`/`fail` 两处 `auditor.record` 传 `profileName`（从 `agentName` 入参带入）
- [x] T016 [P] [US3] 新增 `oryxos-web/.../controller/PricingApiController.java` + `controller/dto` 定价 DTO：模型定价 CRUD（列出/新增/更新/删除，`GET/POST/PUT/DELETE /api/v1/pricing`）
- [x] T017 [P] [US3] 新增 `PricingApiControllerTest.java`：模型定价 CRUD 透传（新增/更新/删除/重复冲突 409）

**Checkpoint**: 成本可配置、可落库、可验证。

---

## Phase 3: User Story 1 - LLM 调用汇总查询（Priority: P1）🎯 MVP

**Goal**: 提供 LLM 调用聚合查询（次数/总token/总成本/成功率/平均耗时），按模型/provider 分组。

**Independent Test**: 预置 `llm_calls` 记录，断言汇总与分组正确。

### Tests for US1

- [x] T018 [US1] 新增 `oryxos-web/src/test/java/io/oryxos/web/audit/AuditMetricsServiceTest.java`（LLM 部分）：预置数据断言次数/成功率/耗时/成本聚合正确——先写、确认失败

### Implementation for US1

- [x] T019 [US1] `LlmCallRepository.java` 加派生查询方法：`findByCreatedAtBetween(Instant from, Instant to)`
- [x] T020 [US1] 新增 `oryxos-web/src/main/java/io/oryxos/web/audit/AuditMetricsService.java`：Java 内存聚合 LLM 汇总（count/successRate/totalTokens/totalCostMicros/avgDurationMs）
- [x] T021 [US1] 新增 `oryxos-web/src/main/java/io/oryxos/web/controller/AuditApiController.java`：`GET /api/v1/audit/llm/summary`、`/by-model`、`/by-provider`（+ DTO record）

**Checkpoint**: LLM 汇总查询可用（MVP）。

---

## Phase 4: User Story 2 - 工具调用汇总查询（Priority: P1）

**Goal**: 提供工具调用聚合查询（次数/成功率/平均耗时），按工具名分组。

**Independent Test**: 预置 `tool_invocations` 记录，断言汇总与分组正确。

### Tests for US2

- [x] T022 [US2] `AuditMetricsServiceTest.java` 补工具部分：预置数据断言次数/成功率/耗时正确——先写、确认失败

### Implementation for US2

- [x] T023 [US2] `ToolInvocationRepository.java` 加 `findByCreatedAtBetween(Instant from, Instant to)`
- [x] T024 [US2] `AuditMetricsService.java` 加工具聚合；`AuditApiController.java` 加 `GET /api/v1/audit/tool/summary`、`/by-name`（+ DTO）

**Checkpoint**: 工具汇总查询可用。

---

## Phase 5: User Story 4 - 按 Agent 下钻（Priority: P2）

**Goal**: 按 Agent（profile_name）聚合 LLM + 工具调用量/成本，无状态调用也能归属。

**Independent Test**: 预置多个 Agent 的调用（含无状态 invoke），断言按 Agent 归集正确。

### Implementation for US4

- [x] T025 [US4] `AuditMetricsService.java` 加按 Agent 聚合；`AuditApiController.java` 加 `GET /api/v1/audit/by-agent`；补单测断言无状态调用归属

**Checkpoint**: 按 Agent 下钻可用。

---

## Phase 6: User Story 5 - 明细下钻（Priority: P2）

**Goal**: 提供 LLM/工具调用明细查询，可下钻查看单条记录。

**Independent Test**: 调明细接口，断言返回记录字段正确。

### Implementation for US5

- [x] T026 [US5] `AuditApiController.java` 加 `GET /api/v1/audit/llm`、`/audit/tool`（`limit` 缺省 100、cap 500）+ DTO；补单测

**Checkpoint**: 明细下钻可用。

---

## Phase 7: 报表看板前端（FR-010~013）

**Goal**: 管理台「报表」页展示 KPI 卡片 + 手写图表 + 明细下钻 + 时间窗切换；Provider 表单加价格字段。

- [x] T027 [P] `oryxos-web/src/main/frontend/src/App.vue`：新增「模型定价」管理（Provider 下按模型配置 promptPrice/completionPrice）
- [x] T028 `oryxos-web/src/main/frontend/src/App.vue`：加「报表」导航项 + 报表页（复用 KPI 卡片/时间窗切换/表格；手写 SVG 横向条形图展示模型/工具/Agent 分布；明细表格下钻）

**Checkpoint**: 看板页可用。

---

## Phase 8: Polish & Cross-Cutting

- [x] T029 全量门禁 `mvn clean verify` 全绿（含 Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs + 全部测试）
- [x] T030 按 `quickstart.md` 走一遍端到端验证（配价 → 触发调用 → 聚合查询 → 看板）
- [x] T031 [P] 同步 `config/application.yml.example` 若有价格种子说明；`CLAUDE.md` 模块表/`TechnicalSolution.md` §10 无需改（未新建模块，仅审计表加列，在 plan 中已声明）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 1)**: 无依赖，先做，阻塞所有故事
- **US3 (Phase 2)**: 依赖 Foundational；是 US1（成本字段）的前置
- **US1 (Phase 3)**: 依赖 US3（成本计算）+ Foundational；MVP
- **US2 (Phase 4)**: 依赖 Foundational；可与 US1 并行
- **US4 (Phase 5)**: 依赖 US3（profile_name 冗余写入）→ 可与 US1/US2 之后做
- **US5 (Phase 6)**: 依赖 US1/US2（查询接口）
- **前端 (Phase 7)**: 依赖所有后端接口
- **Polish (Phase 8)**: 依赖全部

### 关键依赖链

```
Foundational（迁移+契约+审计写入）
   └─ US3 成本计算（价格 CRUD + costMicros 落库 + profileName 冗余）
        ├─ US1 LLM 汇总（含成本） ──┐
        │                          ├─ US5 明细 ── 前端看板 ── Polish
        └─ US4 按 Agent ───────────┘
US2 工具汇总（依赖 Foundational，与 US1 并行）
```

### Parallel Opportunities

- Phase 1 内：T001/T002、T003/T004/T005 可并行
- US1 与 US2 可并行（不同 Repository/Service 方法）
- 前端 T027（价格字段）与 T028（报表页）可拆并行

---

## Implementation Strategy

### MVP First（US3 + US1）

1. Foundational（迁移 + 契约）
2. US3 成本计算（价格 + 落库）
3. US1 LLM 汇总查询 → **STOP 验证**（这是「成本报表」的最小闭环）

### Incremental Delivery

US3 → US1（MVP）→ US2 → US4 → US5 → 前端 → Polish，每步增量可测。

---

## Notes

- 测试方法名英文（如 `costWithFractionalPrice_roundsToMicro`），课件/文档原文用 `@DisplayName` 保留
- 审计 fail-open/fail-closed 语义不变；新列可空，历史行 null 显示「未计量/未归属」
- 不自动 commit/push，同步时机由用户决定
