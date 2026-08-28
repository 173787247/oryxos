# Tasks: Tool Policy 工具策略

**Input**: Design documents from `/specs/020-tool-policy/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R8）、data-model.md、contracts/policy-api.md、quickstart.md

**Tests**: 包含测试任务——项目质量门禁要求核心逻辑有单测覆盖（宪法「开发流程与质量门禁」），且收敛算法的确定性（FR-003/SC-003）与「零策略零破坏」（SC-001）必须测试钉死。

**Organization**: 按用户故事分组；策略引擎（契约+收敛+存储）一次成型在 Foundational，各故事承载消费面与独立验收——US1（全局 deny 三道保险）为 MVP，US2（按 Agent 覆写）、US3（策略可视）依次叠加。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）

## Path Conventions

Maven 多模块单体，涉及 oryxos-core / oryxos-storage / oryxos-web / oryxos-cli / oryxos-boot 五个既有模块（见 plan.md「Source Code」）。

---

## Phase 1: Setup

**Purpose**: 存储地基先行

- [X] T001 [P] 在 oryxos-storage/src/main/resources/schema.sql 末尾追加 `tool_policy_rules` 表（DDL 与注释见 data-model.md；`CREATE TABLE IF NOT EXISTS`，镜像 `sandbox_whitelist` 段落风格）
- [X] T002 [P] 修改 oryxos-storage/src/main/java/io/oryxos/storage/AuditSchemaUpgrade.java：幂等 ALTER 为 `tool_invocations` 加 `blocked_by VARCHAR(16)` 可空列（沿用既有 PRAGMA table_info 检查模式）；oryxos-storage/src/test/java/io/oryxos/storage/AuditSchemaUpgradeTest.java 追加该列的升级用例

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 策略契约 + 收敛引擎 + 审计标记通道——三个故事共同依赖的核心

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [X] T003 [P] 新建 oryxos-core/src/main/java/io/oryxos/core/policy/ToolPolicyService.java：`PolicyDecision check(String agentName, String toolName)`（allowed + 命中规则人话描述）、`List<String> filterAllowed(String agentName, List<String> toolNames)`、`ALLOW_ALL` 常量空实现（零策略零破坏锚点，R1）
- [X] T004 [P] 新建 JPA 实体 oryxos-storage/src/main/java/io/oryxos/storage/ToolPolicyRule.java（字段映射见 data-model.md；镜像 SandboxWhitelistRow 风格）与 oryxos-storage/src/main/java/io/oryxos/storage/ToolPolicyRuleRepository.java（findAll、existsByRuleTypeAndAgentNameAndPattern、deleteById）
- [X] T005 修改 oryxos-core/src/main/java/io/oryxos/core/provider/ToolInvocationAuditor.java（依赖 T002）：`record` 加 `blockedBy` 参数重载（旧签名委托 null）；oryxos-storage/src/main/java/io/oryxos/storage/JpaToolInvocationAuditor.java 与 ToolInvocation.java 落列
- [X] T006 新建 oryxos-storage/src/main/java/io/oryxos/storage/ToolPolicyServiceImpl.java（依赖 T003/T004）：实现 ToolPolicyService——每次实时读全量规则（零缓存，R6）；收敛三步固定序 AGENT_DENY > (GLOBAL_DENY − AGENT_EXEMPT) > 允（R5）；pattern 匹配 = 精确名 或 `server:*` 经注入的 `Function<String,String> mcpOwnerLookup` 判归属（R3）；PolicyDecision 携带命中规则人话描述（审计原因/管理台共用）
- [X] T007 [P] 新建单测 oryxos-storage/src/test/java/io/oryxos/storage/ToolPolicyServiceImplTest.java（依赖 T006，mock repository）：GLOBAL_DENY 命中/未命中、EXEMPT 只解除全局 deny、AGENT_DENY 最终收紧（三重叠加）、`server:*` 通配经 ownerLookup 命中而非名字前缀、精确名与通配同层等效、有效集 ⊆ 声明集、空规则=全允
- [X] T008 修改 oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java（依赖 T006）：@Bean 装配 ToolPolicyServiceImpl（注入 repository + `toolRegistry.mcpToolOwners()::get`）。同任务新建 oryxos-web/src/main/java/io/oryxos/web/security/ToolPolicyStartupCheck.java（镜像 018 ApiKeyStartupCheck：`ApplicationRunner` + `@ConditionalOnWebApplication(SERVLET)`，仅 WARN 不阻断）：启动扫描存量规则——pattern 指向未知工具/未知 Agent 的规则告警（FR-008 加载期校验）、策略致某 Agent 有效工具集全空时告警（FR-011）；配套单测 oryxos-web/src/test/java/io/oryxos/web/security/ToolPolicyStartupCheckTest.java（两分支 WARN 断言 + 正常配置无 WARN，镜像 ApiKeyStartupCheckTest 的 ListAppender 模式）

**Checkpoint**: 收敛引擎单测全绿——消费面实现可以开始

---

## Phase 3: User Story 1 - 全局 deny 三道保险 (Priority: P1) 🎯 MVP

**Goal**: 一条全局 deny 管住所有 Agent：prompt 不可见、执行拒绝、审计留痕；零策略零破坏

**Independent Test**: quickstart V1/V2——空规则时全量现状；配 GLOBAL_DENY 后模型清单不含该工具、幻觉调用被拒且 `blocked_by='policy'` 可查

- [X] T009 [US1] 修改 oryxos-core/src/main/java/io/oryxos/core/agent/PromptBuilder.java（依赖 T003/T008）：构造注入 ToolPolicyService（默认 ALLOW_ALL 保旧构造兼容），`resolveTools` 对解析结果按 `filterAllowed(profile.name(), …)` 过滤（事前保险，R2）
- [X] T010 [US1] 修改 oryxos-core/src/main/java/io/oryxos/core/agent/ToolExecutor.java（依赖 T003/T005）：构造注入 ToolPolicyService（旧构造委托 ALLOW_ALL，镜像其 mcpToolOwners 演进模式）；`execute` 在 `checkMcpAuthorization` 之后按执行瞬间策略 `check`，拒绝走 `fail` 变体——错误信息含命中规则描述、审计落 `blockedBy='policy'`（事中+事后保险，FR-005/FR-006）
- [X] T011 [P] [US1] core 拦截测试（依赖 T009/T010）：新建 oryxos-core/src/test/java/io/oryxos/core/policy/PolicyInterceptTest.java——stub ToolPolicyService 断言 PromptBuilder 过滤后清单不含被拒工具、ToolExecutor 拒绝时工具零执行且 auditor 收到 blockedBy='policy'、ALLOW_ALL 时两拦截点行为与现状完全等价（既有测试全部不改仍绿）
- [X] T012 [US1] 新建 oryxos-web/src/main/java/io/oryxos/web/controller/PolicyApiController.java（依赖 T004）：`GET /api/v1/tool-policy`（本任务先返回 rules 列表；effective 视图 T018 补）、`POST /api/v1/tool-policy/rules`（枚举/agentName 配套校验 400、重复 409、未知目标告警标记、created_by 取管理台 session 用户名或 anonymous）、`DELETE /api/v1/tool-policy/rules/{id}`（404/200）——契约见 contracts/policy-api.md §3，镜像 SandboxWhitelistController
- [X] T013 [P] [US1] 新建 oryxos-web/src/test/java/io/oryxos/web/controller/PolicyApiControllerTest.java（依赖 T012）：三端点 CRUD、GLOBAL_DENY 带 agentName → 400、AGENT_EXEMPT 缺 agentName → 400、重复规则 409、未知工具名保存成功且带告警标记
- [X] T014 [US1] 新建 oryxos-boot/src/test/java/io/oryxos/boot/ToolPolicyE2ETest.java（依赖 T009~T012；镜像 SseStreamingE2ETest 的 mock provider 模式）：空规则时 mock 全链路现状（SC-001）→ REST 配 GLOBAL_DENY save_memory → 事前（会话响应不触发工具）+ 事中（mock 第一轮固定调 save_memory 被拒、失败回填、最终回复含被禁解释）+ 事后（audit 查询 `blocked_by='policy'` 命中）→ 删规则热更新后恢复（SC-004）

**Checkpoint**: quickstart V1/V2/V5 可走通——MVP 可交付

---

## Phase 4: User Story 2 - 按 Agent 覆写 (Priority: P2)

**Goal**: 例外放宽（EXEMPT）与定向收紧（AGENT_DENY）端到端成立，三重叠加收敛确定

**Independent Test**: quickstart V4——全局 deny + ops 例外 → 仅 ops 可用；再加 AGENT_DENY → ops 也被拒；GET 可见裁决依据

- [X] T015 [US2] 在 oryxos-boot/src/test/java/io/oryxos/boot/ToolPolicyE2ETest.java 追加（依赖 T014，同文件串行）：双 Agent 场景——GLOBAL_DENY save_memory + agent A 的 EXEMPT → A 工具照常执行、B 被拒；再加 A 的 AGENT_DENY → A 也被拒（三重叠加终态，SC-003）；全程审计与热更新口径复验
- [X] T016 [P] [US2] 在 oryxos-storage/src/test/java/io/oryxos/storage/ToolPolicyServiceImplTest.java 追加（依赖 T007）：EXEMPT 用通配豁免 `server:*`、精确 EXEMPT 只豁免单工具、EXEMPT 不能授予声明集之外的工具（有效集 ⊆ 声明集恒成立）

**Checkpoint**: quickstart V3/V4 可走通——US1+US2 独立可测

---

## Phase 5: User Story 3 - 策略可视 (Priority: P3)

**Goal**: 管理台策略页看得见改得动；有效工具集与 removed 原因可见；审计可按策略拒绝筛选

**Independent Test**: quickstart V6——策略页三组规则增删 + 每 Agent 有效集与命中规则展示；审计页筛出策略拒绝记录

- [X] T017 [US3] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/PolicyApiController.java（依赖 T012，同文件串行）：`GET /api/v1/tool-policy` 补 effective 视图——遍历 ProfileRegistry 全部 Agent，declared（profile.tools()+MCP 绑定）经 ToolPolicyService 求 effective 与 removed[]（toolName + check 的命中描述），契约见 policy-api.md §3
- [X] T018 [US3] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/AuditApiController.java：tool-invocations 查询加 `blockedBy` 筛选参数（透传 repository 过滤）；oryxos-web/src/test/java/io/oryxos/web/controller/PolicyApiControllerTest.java 追加 effective 视图断言、AuditApiController 既有测试类追加筛选用例
- [X] T019 [US3] 修改 oryxos-web/src/main/frontend/src/App.vue（依赖 T017）：新增「工具策略」导航页——三组规则表格（增删、类型/Agent/pattern/来源/时间、未知目标告警标记）+ 每 Agent 有效工具集卡片（declared/effective/removed 含原因）；审计页筛选器加「策略拒绝」选项（blockedBy=policy）
- [X] T020 [P] [US3] CLI 场景确认（无代码改动预期）：验证 `oryxos chat` 下被 deny 工具的调用表现为工具失败回填 + 模型解释（既有打字机的 tool_end success=false 提示已覆盖）；如需文案微调在 oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/CliChannel.java 完成并在本任务注明

**Checkpoint**: 全部故事独立可测

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 文档与全量验收

- [X] T021 [P] 文档同步：website/zh/docs 与 website/docs 新增或扩写治理节（工具策略：规则形态/收敛语义/与沙箱正交/API，对齐 contracts/policy-api.md）；docs/CliGuide.md 或部署文档提及策略页入口
- [X] T022 [P] 更新 CLAUDE.md「内置 Tool」节旁或安全相关段落：补一句 Tool Policy 治理层的存在与正交关系（工具面新增第二道减法层，宪法 VI 白名单不变）
- [X] T023 按 quickstart.md 完整走查 V1~V7（V6 浏览器走查复用缓存 Chromium + playwright-core 方式）并记录到 specs/020-tool-policy/acceptance-report.md（SC-001~SC-007 逐项对勾，镜像 018/019 报告形式）
- [X] T024 运行 `mvn verify` 全量质量门禁并清零新增告警（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（T001/T002）**: 互相独立可并行
- **Phase 2（T003~T008）**: T003∥T004 先行；T005 依赖 T002；T006 依赖 T003/T004；T007 依赖 T006；T008 依赖 T006
- **Phase 3（US1）**: 依赖 Phase 2；T009∥T010 之后 T011；T012 独立于拦截点可并行推进；T014 依赖 T009~T012
- **Phase 4（US2）**: T015 依赖 T014（同文件）；T016 依赖 T007（同文件）
- **Phase 5（US3）**: T017 依赖 T012（同文件）；T019 依赖 T017；T018/T020 相对独立
- **Phase 6**: 依赖全部故事完成

### 同文件递进链（禁止并行）

- `PolicyApiController.java`: T012 → T017
- `ToolPolicyE2ETest.java`: T014 → T015
- `ToolPolicyServiceImplTest.java`: T007 → T016
- `PolicyApiControllerTest.java`: T013 → T018

### Parallel Opportunities

- Phase 1：T001 ∥ T002
- Phase 2：T003 ∥ T004；T007 ∥ T008
- Phase 3：T009 ∥ T010（不同文件）；T011 ∥ T012∥T013 双线（core 测试 vs web API）
- Phase 5：T018 ∥ T019（不同文件）∥ T020
- Phase 6：T021 ∥ T022

---

## Parallel Example: Phase 3

```bash
# 拦截双点与 API 面两线并行：
Task: "T009 PromptBuilder 事前过滤"   ∥   Task: "T010 ToolExecutor 事中裁决"
# 然后：
Task: "T011 core 拦截测试"   ∥   Task: "T012 PolicyApiController + T013 其测试"
# 汇合：T014 E2E 三道保险全链路
```

---

## Implementation Strategy

### MVP First（US1 Only）

1. Phase 1 + Phase 2（T001~T008）：表、契约、收敛引擎、装配全就位
2. Phase 3（T009~T014）：三道保险 + 规则 CRUD API
3. **STOP and VALIDATE**: quickstart V1/V2/V5 走通即可演示（配 deny → 模型不可见 + 调用被拒留痕 → 删规则即恢复）
4. US2/US3 依次叠加，各自 checkpoint 独立验收

### 注意

- 「零策略零破坏」是红线：T009/T010 的旧构造必须委托 ALLOW_ALL，全部既有测试**不改动**仍绿（T011 显式断言等价性；吸取 019 AgentServiceTest 教训——新参构造不得破坏既有 stub/verify 交互契约）
- 有效集 ⊆ 声明集恒成立（EXEMPT 不做加法）由 T007/T016 双重钉死
- 每完成一个 Phase 提交一次（scope 按主要触点：core/storage/web）
