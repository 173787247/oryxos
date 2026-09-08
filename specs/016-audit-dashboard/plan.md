# Implementation Plan: 审计查询接口与报表看板（Audit Query & Dashboard）

**Branch**: `016-audit-dashboard` | **Date**: 2026-08-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-audit-dashboard/spec.md`

## Summary

在两张既有审计表（`llm_calls` / `tool_invocations`）之上加一层只读的审计查询接口 + 报表看板。核心动作：为**具体模型（provider + model）**配置 token 单价（管理台 CRUD）、LLM 调用时**写时定格**成本（微元整数）、给审计记录冗余 Agent 归属、提供聚合/明细查询 REST 接口、前端报表页（KPI 卡片 + 手写 SVG 图表 + 明细下钻）。

## Technical Context

**Language/Version**: Java 21（虚拟线程）

**Primary Dependencies**: Spring Boot 3.x、Spring AI Alibaba（仅协议转换 + `@Tool` schema）、Spring Data JPA、SQLite

**Storage**: SQLite（`schema.sql` 手工建表为唯一权威，`ddl-auto: none`，加列走专门升级器）

**Testing**: JUnit 5 + Mockito；`mvn clean verify` 全绿（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + 全部测试）

**Target Platform**: 企业私有部署（单 fat JAR）

**Project Type**: web-service（Spring MVC + 虚拟线程）+ 单文件 Vue3 管理台（`oryxos-web/src/main/frontend/src/App.vue`）

**Performance Goals**: 万级审计记录 Java 内存聚合，秒级返回

**Constraints**: 同步执行 + 虚拟线程（宪法 VII）；无第三方图表库；SQLite `ALTER TABLE` 弱，加列走 schema.sql + 升级器

**Scale/Scope**: 单实例，万级审计记录；5 列迁移 + 2 个聚合查询接口 + 1 个前端报表页

## Constitution Check

*GATE: 逐条核对宪法 8 原则，本特性无违规。*

| 原则 | 核对 |
|------|------|
| I. 自实现 ReAct 循环 | ✅ 不改 `ReActLoop` 循环逻辑，仅扩展审计写入的入参（`ToolExecutor`/`SpringAiProviderServiceImpl` 传 Agent 名与成本） |
| II. Spring AI 仅协议转换 | ✅ 不改 LLM 调用方式，成本计算在 `SpringAiProviderServiceImpl` 内、不引入 Spring AI 自动执行 |
| III. Provider 显式映射 | ✅ 单价挂在独立 `llm_pricing` 表（按 provider+model 索引），不改 Provider 显式映射机制 |
| IV. 一个目录 = 一个 Agent | ✅ 审计冗余 Agent 归属，不改变 Agent 目录定义方式 |
| V. 审计 Day One 落库 | ✅ 不改现有审计写入链路，只加可空列；写入失败行为不变 |
| VI. 安全是地基 | ✅ 只读查询、无新凭证、无明文 key；价格非敏感信息 |
| VII. 同步执行 + 虚拟线程 | ✅ Java 内存聚合同步阻塞，不引入 Reactor/CompletableFuture |
| VIII. 目录配置即 Agent + 状态外置 + 手工建表 | ✅ 加列走 schema.sql + 升级器（沿用 `ScheduleSchemaUpgrade` 模式），不依赖 Hibernate 迁移 |

**结论**：无违规，无需 Complexity Tracking 论证。

## Project Structure

### Documentation (this feature)

```text
specs/016-audit-dashboard/
├── plan.md              # 本文件
├── research.md          # Phase 0：技术决策记录
├── data-model.md        # Phase 1：数据模型与迁移
├── contracts/           # Phase 1：REST API 契约
│   └── rest-api.md
├── quickstart.md        # Phase 1：验证指南
└── tasks.md             # Phase 2（/speckit-tasks 产出，非本命令）
```

### Source Code (repository root)

```text
oryxos-storage/
├── src/main/resources/schema.sql              # llm_calls/tool_invocations 加列 + llm_pricing 新表 + 索引
└── src/main/java/io/oryxos/storage/
    ├── LlmPricing.java                         # 新增：模型定价实体
    ├── LlmPricingRepository.java               # 新增：按 provider+model 查价
    ├── LlmCall.java                            # + profileName/costMicros
    ├── ToolInvocation.java                     # + profileName
    ├── LlmCallRepository.java                  # + 时间窗/聚合查询方法
    ├── ToolInvocationRepository.java           # + 时间窗查询方法
    ├── JpaLlmCallAuditor.java                  # 写新列
    ├── JpaToolInvocationAuditor.java           # 写 profileName
    └── AuditSchemaUpgrade.java                 # 新增升级器（参考 ScheduleSchemaUpgrade）

oryxos-core/src/main/java/io/oryxos/core/
├── provider/
│   ├── LlmCallAuditor.java                     # record + profileName/costMicros
│   └── ModelPricing.java                       # 新增：跨模块定价值对象（provider/model/promptPrice/completionPrice）
└── agent/ToolInvocationAuditor.java            # record + profileName

oryxos-provider/src/main/java/io/oryxos/provider/
└── SpringAiProviderServiceImpl.java            # 按 (provider, model) 查价算成本 + 传 profile

oryxos-web/
├── src/main/java/io/oryxos/web/
│   ├── controller/
│   │   ├── PricingApiController.java           # 新增：模型定价 CRUD
│   │   └── AuditApiController.java             # 新增：审计聚合/明细查询
│   ├── controller/dto/                          # 新增 Audit/Pricing XxxView record
│   └── audit/AuditMetricsService.java          # 新增：Java 内存聚合（仿 KnowledgeMetricsService）
└── src/main/frontend/src/App.vue               # 报表页 + 模型定价表单
```

**Structure Decision**: 沿用既有 9 模块，不新建模块——成本与 Agent 归属的契约放 `oryxos-core`（依赖倒置），持久化与迁移放 `oryxos-storage`，成本计算放 `oryxos-provider`，查询接口与前端看板放 `oryxos-web`。

## Complexity Tracking

无违规，无需填写。
