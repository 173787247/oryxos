# Contract: Tool Policy 行为与 API

**Feature**: 020-tool-policy | **Date**: 2026-08-28 | 原则五：接口即承诺

## 1. 策略语义承诺

1. **只做减法**：有效工具集永远 ⊆ Agent 声明集（`tools:` + MCP 绑定）；策略不能授予未声明的工具。
2. **收敛确定**：AGENT_DENY > (GLOBAL_DENY − AGENT_EXEMPT) > 允许，固定三步、同输入同结果。
3. **三道保险**：被拒工具不进模型工具清单（事前）；执行层按执行瞬间最新策略拒绝（事中，防幻觉调用与热更新窗口）；`tool_invocations.blocked_by='policy'` 留痕（事后，可筛）。
4. **与沙箱正交**：策略放行不豁免沙箱白名单；两者独立配置、叠加生效。
5. **零策略零破坏**：`tool_policy_rules` 为空时一切行为与现状一致。
6. **热更新**：规则增删即刻对下一次消息处理生效，无需重启。

## 2. 规则形态

| rule_type | agent_name | pattern | 语义 |
|-----------|-----------|---------|------|
| `GLOBAL_DENY` | —（空） | `shell` / `github-mcp:*` | 所有 Agent 禁用匹配工具 |
| `AGENT_EXEMPT` | `ops-agent` | `shell` | 该 Agent 豁免全局 deny 中的匹配项 |
| `AGENT_DENY` | `support-agent` | `http_post` | 该 Agent 额外禁用（例外救不回） |

pattern 两种形态：工具精确名；`server:*`（MCP server 级通配，按工具的注册归属判定，非名字前缀）。精确名与通配同层等效计入命中。

## 3. REST API（受 018 认证门禁保护）

### GET `/api/v1/tool-policy`

```json
// data
{
  "rules": [
    { "id": 1, "ruleType": "GLOBAL_DENY", "agentName": null, "pattern": "shell",
      "createdAt": "2026-08-28T10:00:00Z", "createdBy": "admin", "unknownTarget": false }
  ],
  "effective": [
    { "agentName": "ops-agent",
      "declared": ["shell", "read_file"],
      "effective": ["shell", "read_file"],
      "removed": [] },
    { "agentName": "kb-tester",
      "declared": ["shell", "retrieve_knowledge"],
      "effective": ["retrieve_knowledge"],
      "removed": [ { "toolName": "shell", "reason": "全局禁用规则 #1（shell）" } ] }
  ]
}
```

### POST `/api/v1/tool-policy/rules`

```json
// 请求
{ "ruleType": "AGENT_EXEMPT", "agentName": "ops-agent", "pattern": "shell" }
// 校验：ruleType 枚举；GLOBAL_DENY 不得带 agentName，另两类必须带；重复规则 → 409；
// pattern 指向未知工具/Agent → 保存成功但响应与加载日志携带告警（不阻断，MCP 可能后接入）
```

### DELETE `/api/v1/tool-policy/rules/{id}`

不存在 → 404；成功 → 200，规则即刻失效。

### 审计筛选（016 查询 API 扩展）

`GET /api/v1/audit/tool-invocations?blockedBy=policy` → 仅策略拒绝的调用记录。

## 4. 执行面表现

- 模型清单：被拒工具不出现（Agent 被问能力时不会提及）。
- 幻觉调用：`ToolResult` 失败，`errorMessage` 形如「被平台策略禁止：命中全局禁用规则（shell）」——模型可向用户转述。
- 全空工具集：Agent 照常纯对话运行；策略加载与管理台对该 Agent 显示告警。

## 5. 兼容性承诺

- `AGENT.md` frontmatter 语义零变化；`/api/v1` 既有端点零变化；`tool_invocations` 只加可空列（旧行为不受影响）。
- 管理台新增「工具策略」页，不改动既有页面。
