# Research: Tool Policy 工具策略

**Feature**: 020-tool-policy | **Date**: 2026-08-28

技术上下文无 NEEDS CLARIFICATION；本文记录关键技术裁决，全部基于实地摸底（`ToolRegistry`/`mcpToolOwners`、`PromptBuilder.resolveTools`、`ToolExecutor.execute` 的 MCP 授权校验先例、`sandbox_whitelist` 三件套、`AuditSchemaUpgrade` 的 ALTER 模式）。

## R1. 策略判定的核心抽象：core 契约 + storage 实现（依赖倒置）

**Decision**: oryxos-core 新增 `policy/ToolPolicyService` 接口：`PolicyDecision check(String agentName, String toolName)`（返回 允许 / 拒绝+命中规则说明）与 `List<String> filterAllowed(String agentName, List<String> toolNames)`。实现 `ToolPolicyServiceImpl` 在 oryxos-storage，`OryxOsRuntime` @Bean 装配（镜像 `WebUserService`/`JpaSandboxWhitelistStore` 模式）。未装配（null/空实现）时一切放行——旧测试与零策略部署零破坏。

**Rationale**: 两个消费点都在 core（PromptBuilder、ToolExecutor），契约必须进 core；实现依赖 JPA 归 storage。与项目全部治理组件同构。

## R2. 拦截点：复用两处既有缝隙，不新增循环逻辑

**Decision**: 事前——`PromptBuilder.resolveTools` 在既有解析结果上按 `filterAllowed` 过滤（凡进 prompt 必过策略）；事中——`ToolExecutor.execute` 在 `checkMcpAuthorization` 之后追加 `policy.check`，拒绝走既有 `fail()` 路径（可读原因「被平台策略禁止(命中规则 X)」）。ReActLoop 零改动。

**Rationale**: `checkMcpAuthorization` 已是「执行前授权校验」的既有先例，policy 是同位置的第二道；事前/事中双查保证热更新窗口内（本轮 prompt 旧集）的调用也被兜住（spec Edge Case「热更新与进行中的 ReAct」）。

## R3. MCP 通配的实现语义：走归属表而非名字前缀

**Decision**: MCP 工具以**原始名**注册（摸底确认：`registerMcpTool(serverName, tool)`，前缀不在工具名里），server 归属在 `ToolRegistry.mcpToolOwners()`（活视图）。策略规则 `github-mcp:*` 的匹配 = 「`mcpToolOwners.get(toolName) == "github-mcp"`」，通过向 `ToolPolicyServiceImpl` 注入 owner 查找函数（`Function<String, String>`）实现；精确名规则直接匹配工具名，精确名优先于通配（spec FR-003）。

**Rationale**: 名字前缀匹配会落空（注册名根本没有前缀）；归属表是既有权威真相源，MCP server 增删即刻反映。

## R4. 存储：`tool_policy_rules` 单表（镜像 sandbox_whitelist）

**Decision**: 新表 `tool_policy_rules`：`rule_type`（GLOBAL_DENY / AGENT_EXEMPT / AGENT_DENY）、`agent_name`（GLOBAL_DENY 为空）、`pattern`（工具名或 `server:*`）、`created_at`、`created_by`（管理台账号 / API 调用方标识）；`UNIQUE(rule_type, agent_name, pattern)`。`CREATE TABLE IF NOT EXISTS` 进 schema.sql（新表非 ALTER）。FR-013 追溯 = 行级 `created_at + created_by`（最低口径，删除无历史——与 Clarifications 裁决一致）。

**Rationale**: 与 `sandbox_whitelist` 完全同构（category→rule_type、entry_value→pattern），管理台增删即落库、重启保留、Clarifications Q1 的 SQLite 裁决落地。

## R5. 有效集收敛算法（FR-003 的确定性）

**Decision**: `filterAllowed(agent, declared)` 对每个工具名求值：
1. 命中 AGENT_DENY(agent) → 拒（最终收紧，例外救不回）；
2. 命中 GLOBAL_DENY 且未命中 AGENT_EXEMPT(agent) → 拒；
3. 其余 → 允。
精确名与通配都参与各层匹配，同层内精确名与通配等效（都算命中）；跨层优先级固定如上。`check` 返回命中规则的人话描述（管理台「为什么」列 / 审计原因共用）。

**Rationale**: 三条固定顺序穷尽全部叠加情形（spec US2 场景 3），无歧义；「例外只解除全局 deny」由第 1 条位于第 2 条之前保证。

## R6. 热更新：实时查库，零缓存

**Decision**: `ToolPolicyServiceImpl` 每次 `filterAllowed`/`check` 实时读 `tool_policy_rules`（一次全表读入内存匹配——策略行数量级为个位到几十，SQLite 单查微秒级）。不做缓存/失效通知。

**Rationale**: SC-004「下一次消息处理即生效」由零缓存天然满足；策略读取相对 LLM 调用的耗时可忽略，为它建缓存失效机制是过度工程（YAGNI）。

## R7. 审计标记：`tool_invocations` 加 `blocked_by` 列

**Decision**: `AuditSchemaUpgrade` 追加 `ALTER TABLE tool_invocations ADD COLUMN blocked_by VARCHAR(16)`（既有幂等 PRAGMA 检查模式）；`ToolInvocationAuditor` 契约与 `ToolExecutor.fail` 增加带 `blockedBy` 的重载（旧签名委托 null）。策略拒绝写 `'policy'`；本期只保证 policy 标记（沙箱/MCP 拒绝的归类回填留后续），`blocked_by='policy'` 即满足 FR-006 的可区分筛选。审计查询 API（016）加同名筛选参数。

**Rationale**: error_message 文本约定不可靠（无法稳定筛选）；一列带类型标记是最小可筛结构，ALTER 走既有升级类模式（宪法 VIII）。

## R8. REST 与管理台（镜像 sandbox 白名单面）

**Decision**: `PolicyApiController`：`GET /api/v1/tool-policy`（全量规则 + 每个 Agent 的有效工具集及命中规则）、`POST /api/v1/tool-policy/rules`（添加规则）、`DELETE /api/v1/tool-policy/rules/{id}`（删除）；`created_by` 取当前管理台 session 用户名，无认证时记 `anonymous`（与现状治理端点口径一致）。管理台新增「工具策略」页（导航与沙箱白名单页同级）：三组规则的增删 + 每 Agent 有效工具集展示（含被移除工具的命中规则）。校验：pattern 指向未知工具/未知 Agent 时保存成功但页面与加载日志给出告警（FR-008/FR-011——不阻断，MCP 工具可能后接入）。

**Rationale**: 与 `SandboxWhitelistController` 同构；有效集展示直接复用 `check` 的命中规则描述，避免两套裁决逻辑。
