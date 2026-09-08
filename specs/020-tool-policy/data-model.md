# Data Model: Tool Policy 工具策略

**Feature**: 020-tool-policy | **Date**: 2026-08-28

## tool_policy_rules（新表）

平台治理层的策略规则。与 `.oryxos/agents/`（Agent 作者层）分离；镜像 `sandbox_whitelist` 的「管理台可编辑治理配置」模式。

```sql
-- tool_policy_rules：工具策略（020-tool-policy）——平台管理员的治理层，独立于 AGENT.md 的 tools: 声明。
-- rule_type：GLOBAL_DENY（全局禁用，agent_name 为空）/ AGENT_EXEMPT（指定 Agent 豁免全局 deny）/
--            AGENT_DENY（指定 Agent 额外收紧）。pattern 为工具精确名或 MCP server 通配（server:*）。
-- created_by 记录规则来源（管理台账号 / API 调用方），满足「配置即责任」最低追溯口径（FR-013）。
-- 新表，CREATE TABLE IF NOT EXISTS，非 ALTER，存量库无迁移风险。
CREATE TABLE IF NOT EXISTS tool_policy_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_type VARCHAR(16) NOT NULL,
    agent_name VARCHAR(255),
    pattern VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(255),
    UNIQUE (rule_type, agent_name, pattern)
);
```

### 字段约束

| 字段 | 约束 | 来源 |
|------|------|------|
| `rule_type` | 枚举三值；GLOBAL_DENY 时 `agent_name` MUST 为空，另两类 MUST 非空 | FR-001/FR-002 |
| `pattern` | 工具精确名或 `server:*` 通配（仅此一种通配形态）；未知工具/Agent 告警不阻断 | FR-007/FR-008 |
| `created_by` | 管理台 session 用户名 / API 调用方；无认证部署记 `anonymous` | FR-013 |
| 唯一约束 | 同类型同 Agent 同 pattern 不重复 | 幂等增删 |

## tool_invocations 变更（ALTER，走 AuditSchemaUpgrade）

```sql
ALTER TABLE tool_invocations ADD COLUMN blocked_by VARCHAR(16);  -- 可空；'policy' = 策略拒绝（FR-006）
```

策略拒绝的调用：`success=false`、`error_message` 含命中规则的人话描述、`blocked_by='policy'`；其余调用该列为空。审计查询 API 支持按 `blocked_by` 筛选。

## 核心契约（oryxos-core，依赖倒置）

### ToolPolicyService（新接口，`core/policy/`）

| 方法 | 语义 |
|------|------|
| `PolicyDecision check(String agentName, String toolName)` | 单工具裁决：允许 / 拒绝 + 命中规则描述（审计原因与管理台「为什么」共用） |
| `List<String> filterAllowed(String agentName, List<String> toolNames)` | 批量过滤（PromptBuilder 事前用） |

- 空实现 `ALLOW_ALL` 常量：未配置策略/旧构造路径走它，行为与现状一致（零破坏锚点）。
- MCP 通配判定依赖注入的 `Function<String, String> mcpOwnerLookup`（`ToolRegistry.mcpToolOwners()::get` 活视图）。

### 有效集收敛（FR-003，固定三步）

```
对 agent 声明集中的每个工具名 t：
  1. 命中 AGENT_DENY(agent, t)                    → 拒（最终收紧，例外救不回）
  2. 命中 GLOBAL_DENY(t) 且未命中 AGENT_EXEMPT(agent, t) → 拒
  3. 其余                                          → 允
匹配：精确名或 server 通配（经 mcpOwnerLookup）任一命中即命中；有效集永远 ⊆ 声明集。
```

## 传输态视图（oryxos-web）

- **PolicyRuleView**: id / ruleType / agentName / pattern / createdAt / createdBy（+ 未知目标告警标记）。
- **EffectiveToolSetView**: agentName / declared[] / effective[] / removed[]（每项含 toolName + 命中规则描述）。派生自 `ToolPolicyService`，不落库。

## 不新增的

- `AGENT.md` frontmatter 零改动（治理层不经作者层表达，FR-002）。
- 无策略变更历史表（最低追溯口径，Clarifications 裁决）；无参数级策略字段。
