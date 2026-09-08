# Implementation Plan: Tool Policy 工具策略

**Branch**: `020-tool-policy` | **Date**: 2026-08-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/020-tool-policy/spec.md`

## Summary

平台管理员的工具治理层：全局 deny + 按 Agent 例外/收紧，三道保险（prompt 不可见 / 执行拒绝 / 审计留痕）。技术路线：core 新增 `ToolPolicyService` 契约（`ALLOW_ALL` 默认，零策略零破坏），实现 `ToolPolicyServiceImpl` 镜像 `sandbox_whitelist` 模式落 SQLite 新表 `tool_policy_rules`；拦截复用两处既有缝隙——`PromptBuilder.resolveTools` 事前过滤、`ToolExecutor.execute` 在 MCP 授权校验旁追加事中裁决（ReActLoop 零改动）；MCP 通配走 `mcpToolOwners` 归属表（工具注册名无前缀，名字匹配会落空——摸底结论）；`tool_invocations` 加 `blocked_by` 列（AuditSchemaUpgrade ALTER 模式）支撑可筛审计；REST + 管理台策略页镜像沙箱白名单面。零新依赖、零新模块。

## Technical Context

**Language/Version**: Java 21（虚拟线程，同现状）

**Primary Dependencies**: Spring Boot 3.x、Spring Data JPA、Vue 3（管理台策略页）；零新增依赖

**Storage**: SQLite——新表 `tool_policy_rules`（schema.sql 手工建表）+ `tool_invocations` 加 `blocked_by` 可空列（`AuditSchemaUpgrade` 幂等 ALTER）

**Testing**: JUnit 5——core（收敛算法/三重叠加/ALLOW_ALL 等价）、storage（规则 CRUD/唯一约束）、web（策略 API/有效集视图/审计筛选）、boot E2E（三道保险全链路 + 热更新）；`mvn verify` 全量门禁

**Target Platform**: Linux server（单 fat JAR，同现状）

**Project Type**: Maven 多模块单体——涉及 oryxos-core（契约+两处拦截）、oryxos-storage（表+实现）、oryxos-web（API+策略页+审计筛选）、oryxos-cli（OryxOsRuntime 装配）

**Performance Goals**: 策略判定实时查库零缓存（规则量级几十行，微秒级）；热更新生效延迟 ≤ 一次消息往返（SC-004）

**Constraints**: 零策略零破坏（SC-001，`ALLOW_ALL` 锚点）；有效集 ⊆ 声明集（策略只做减法）；策略与沙箱正交（FR-012，两套判定互不感知）

**Scale/Scope**: 规则数几十行；约 11 个新文件（含启动检查及其测试）+ 6 个既有文件小改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | ReActLoop 零改动；拦截在 PromptBuilder/ToolExecutor 的既有缝隙（MCP 授权校验同位先例） | ✅ |
| II Spring AI 边界 | 不涉及（工具清单注入仍走 availableTools 既有通道） | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 目录=Agent / Skill | `AGENT.md` 零改动——治理层与作者层分离正是本特性的立意 | ✅ |
| V 审计 Day One | 策略拒绝照写 `tool_invocations`（blocked_by 标记），拒绝也留痕（FR-014） | ✅ |
| VI 安全是地基 | 策略是白名单之上的独立减法层，正交不豁免（FR-012）；治理配置进 SQLite 与沙箱白名单同权威 | ✅ |
| VII 同步 + 虚拟线程 | 策略判定为同步查库，无异步引入 | ✅ |
| VIII 状态外置 / 手工 schema | 新表 CREATE IF NOT EXISTS；ALTER 走 AuditSchemaUpgrade 幂等模式，不依赖 Hibernate 迁移 | ✅ |
| 模块约束 | 契约进 core、实现在 storage、API 在 web——不新建模块，无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/020-tool-policy/
├── plan.md              # 本文件
├── research.md          # Phase 0：8 项技术裁决（R1~R8）
├── data-model.md        # Phase 1：tool_policy_rules 表 + blocked_by 列 + 收敛算法
├── quickstart.md        # Phase 1：V1~V7 验收走查
├── contracts/
│   └── policy-api.md    # Phase 1：策略语义承诺 + REST API
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/
│   ├── policy/ToolPolicyService.java    # 新增：契约（check/filterAllowed + ALLOW_ALL + PolicyDecision）
│   ├── agent/PromptBuilder.java         # 修改：resolveTools 按策略过滤（事前）
│   └── agent/ToolExecutor.java          # 修改：checkMcpAuthorization 后追加策略裁决（事中）+ fail 带 blockedBy
│   └── provider/ToolInvocationAuditor.java  # 修改：record 加 blockedBy 重载（旧签名委托 null）
└── src/test/java/io/oryxos/core/
    ├── policy/ToolPolicyConvergenceTest.java  # 新增：收敛三步/三重叠加/通配 vs 精确/ALLOW_ALL 等价
    └── agent/…                                 # 修改：两拦截点的策略行为用例并入既有测试类或新增

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── ToolPolicyRule.java              # 新增：JPA 实体
│   ├── ToolPolicyRuleRepository.java    # 新增
│   ├── ToolPolicyServiceImpl.java          # 新增：ToolPolicyService 实现（实时查库 + mcpOwnerLookup 注入）
│   ├── ToolInvocation.java              # 修改：blocked_by 字段
│   ├── JpaToolInvocationAuditor.java    # 修改：新重载落列
│   └── AuditSchemaUpgrade.java          # 修改：幂等 ALTER 加列
└── src/main/resources/schema.sql        # 修改：追加 tool_policy_rules 表

oryxos-web/
├── src/main/java/io/oryxos/web/controller/
│   ├── PolicyApiController.java         # 新增：GET/POST/DELETE + 有效集视图（镜像 SandboxWhitelistController）
│   └── AuditApiController.java          # 修改：tool-invocations 查询加 blockedBy 筛选
├── src/main/java/io/oryxos/web/security/
│   └── ToolPolicyStartupCheck.java      # 新增：加载期告警（未知目标规则/有效集全空，WARN 不阻断，镜像 ApiKeyStartupCheck）
├── src/main/frontend/src/App.vue        # 修改：「工具策略」页（规则增删 + 每 Agent 有效集与 removed 原因）
└── src/test/java/io/oryxos/web/controller/
    └── PolicyApiControllerTest.java     # 新增

oryxos-cli/
└── src/main/java/io/oryxos/cli/OryxOsRuntime.java  # 修改：装配 ToolPolicyServiceImpl（注入 ToolRegistry.mcpToolOwners()::get）

oryxos-boot/
└── src/test/java/io/oryxos/boot/ToolPolicyE2ETest.java  # 新增：三道保险全链路 + 热更新 + 审计筛选
```

**Structure Decision**: 契约进 core（PromptBuilder/ToolExecutor 是消费方），JPA 实现进 storage，API/UI 进 web——与 `sandbox_whitelist`、`WebUserService` 等治理组件完全同构；不新建模块。`ToolExecutor` 的构造函数演进沿用其既有「旧构造委托 + 新参可空则不校验」模式，保证全部既有测试零改动通过。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | core 契约 + storage 实现 | `ToolPolicyService` + `ALLOW_ALL` 默认；未装配即现状（零破坏锚点） |
| R2 | 拦截复用既有缝隙 | resolveTools 事前过滤；execute 在 MCP 授权同位追加事中裁决；ReActLoop 零改动 |
| R3 | MCP 通配走归属表 | 工具注册名无前缀（摸底确认），`server:*` 经 `mcpToolOwners` 判定 |
| R4 | 单表镜像 sandbox_whitelist | rule_type/agent_name/pattern + created_by 行级追溯（Clarifications Q1） |
| R5 | 收敛三步固定序 | AGENT_DENY > (GLOBAL_DENY − EXEMPT) > 允；命中描述供审计/管理台共用 |
| R6 | 实时查库零缓存 | 热更新天然成立；规则量级下缓存是过度工程 |
| R7 | blocked_by 列 | AuditSchemaUpgrade 幂等 ALTER；本期只标 policy，可筛即达 FR-006 |
| R8 | REST/管理台镜像沙箱面 | 有效集视图复用 check 的命中描述，单一裁决逻辑 |
