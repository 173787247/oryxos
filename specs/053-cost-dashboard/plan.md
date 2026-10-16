# Implementation Plan: 成本看板（Cost Dashboard）

**Branch**: `053-cost-dashboard` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/053-cost-dashboard/spec.md`

## Summary

在管理台 `/admin` 新增「成本」页与菜单，只读展示 LLM/工具成本：KPI、按天 LLM 成本趋势、工具调用按 agent 归集、维度筛选、流水明细。复用既有成本账本与查询服务，仅为"时间维度 / 按天聚合 / 工具调用计数 / 筛选器候选值"补充只读查询能力，并把 `/api/v1/cost` 纳入 RBAC 读动作（VIEWER）。不改计费/落账/审计写路径。

## Technical Context

**Language/Version**: Java 21（后端）/ Vue 3 + Vite（既有管理台 SPA）

**Primary Dependencies**: Spring MVC（虚拟线程）、Spring Data JPA；前端沿用 `oryxos-web/src/main/frontend` 现有栈，**不新增图表库**（内联 SVG）

**Storage**: 既有 `cost_ledger_entries`（SQLite/PostgreSQL）——**本特性不建表、不加列、无 Flyway 迁移**

**Testing**: JUnit5 + MockMvc（web 接口契约）、core/storage 查询单测；前端沿用既有测试约定

**Target Platform**: 企业私有部署，单 fat JAR（前端产物随包）

**Project Type**: web-service + 管理台 SPA

**Performance Goals**: 常规数据量下首屏数秒内；按天聚合受 92 天上限约束

**Constraints**: 只读展示，不改落账/审计；人民币元 2 位；成本未开启 404→空状态；RBAC fail-closed 需登记

**Scale/Scope**: 后端约 4 个查询接口（1 扩展 + 3 新增）+ core 查询/summary 扩展 + storage 实现 + RBAC 一处登记 + 前端 1 页/1 菜单

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 说明 |
|------|------|------|
| I. 自实现 ReAct | ✅ N/A | 不触及推理循环 |
| II. Spring AI 仅协议转换 | ✅ N/A | 不触及 Provider/LLM |
| III. Provider 显式映射 | ✅ N/A | — |
| IV. 一个目录=一个 Agent | ✅ N/A | 不触及 Agent/Skill |
| V. 审计 Day One | ✅ 符合 | 纯只读查询，不新增 tool/LLM 调用、不写审计表 |
| VI. 安全是地基 | ✅ 符合 | 新增接口纳入 RBAC 读动作（VIEWER）；不走 Shell/文件白名单 |
| VII. 同步+虚拟线程 | ✅ 符合 | 全同步查询，无 Reactor/CompletableFuture |
| VIII. 状态外置 | ✅ 符合 | 无新状态，读既有账本 |
| 技术栈/模块 | ✅ 符合 | 不新增模块；契约在 core、storage 实现（依赖倒置）；前端零新增重依赖 |
| **X（FR-010 只读）** | ✅ 符合 | 仅加查询与派生字段，`recordLlm/recordTool` 落账与审计表零改动 |

**门禁结论：全绿，无需复杂度论证。** 设计后复查不变。

## Project Structure

### Documentation (this feature)

```text
specs/053-cost-dashboard/
├── plan.md · research.md · data-model.md · quickstart.md
├── contracts/cost-api.md
└── tasks.md   # /speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/cost/
├── CostAttributionQuery.java      # + from/to（Instant，可空）
├── CostAttributionSummary.java    # + llmCallCount / toolCallCount
├── CostLedgerStore.java           # + 时间过滤/按天/工具归集/facets 查询能力
├── CostLedgerService.java         # 聚合：按天分桶(服务端时区)、工具计数、facets、调用数
├── DailyCostPoint.java            # 新增只读 VO
├── ToolUsageEntry.java            # 新增只读 VO
└── CostFacets.java                # 新增只读 VO

oryxos-storage/src/main/java/io/oryxos/storage/
├── JpaCostLedgerStore.java        # 实现时间过滤 + 去重值（JPA 查询）
└── CostLedgerEntryRepository.java # 需要的派生查询（按时间范围/去重）

oryxos-web/src/main/java/io/oryxos/web/
├── controller/CostApiController.java        # /attribution 加 from/to；新增 /daily /tool-usage /facets
├── controller/dto/（DailyCostView/ToolUsageView/CostFacetsView 按需）
└── security/RequestActionResolver.java      # 登记 /api/v1/cost 前缀 → READ 动作（VIEWER）

oryxos-web/src/main/frontend/src/
├── features/cost/CostDashboardView.vue      # 看板页（KPI/趋势SVG/工具归集/筛选/明细/空态）
├── features/cost/*（子组件/请求封装按需）
└── App.vue                                  # 注册路由 + 导航「成本」项

oryxos-web/src/test/java/io/oryxos/web/controller/
└── CostApiControllerTest.java（扩展/新增）  # 契约：from/to、daily 补零、tool-usage、facets、404、RBAC
```

**Structure Decision**: 改动集中在 `oryxos-core`(cost 查询契约+聚合)、`oryxos-storage`(JPA 实现)、`oryxos-web`(接口+RBAC+前端)，不新增模块、不动跨模块契约方向（契约在 core，storage 实现）。按天聚合与工具计数放 service 层（见 research Decision 1/2），storage 只补时间过滤与去重查询。

## Complexity Tracking

> 无宪法违背项，本节留空。
