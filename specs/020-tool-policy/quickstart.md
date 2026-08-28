# Quickstart: Tool Policy 验收

**Feature**: 020-tool-policy | 契约详见 [contracts/policy-api.md](contracts/policy-api.md)

## 前置

```bash
mvn -q -DskipTests package
alias oryxos='java -jar oryxos-boot/target/oryxos-boot-*.jar'
oryxos serve --port 8080 &     # mock provider + 声明了 shell/save_memory 的测试 Agent
```

## V1 — 零策略零破坏（US1 场景 4 / SC-001）

```bash
sqlite3 .oryxos/oryxos.db "SELECT count(*) FROM tool_policy_rules;"   # 0
# 任意 Agent 对话与工具执行与现状一致；mvn verify 全量既有测试通过
```

## V2 — 全局 deny 三道保险（US1 场景 1/2 / SC-002）

```bash
curl -s -X POST localhost:8080/api/v1/tool-policy/rules -H 'Content-Type: application/json' \
  -d '{"ruleType":"GLOBAL_DENY","pattern":"save_memory"}'
# ① 事前：流式/非流式发消息，问 Agent 能力 → 回复不含 save_memory；
# ② 事中：诱导 mock Agent 调 save_memory（mock 第一轮固定调它）→ 工具零执行、
#    失败结果回填、Agent 最终回复解释被禁；
# ③ 事后：
sqlite3 .oryxos/oryxos.db "SELECT tool_name, success, blocked_by, error_message FROM tool_invocations WHERE blocked_by='policy' ORDER BY id DESC LIMIT 3;"
```

## V3 — MCP 通配（US1 场景 3）

```bash
# 挂一个 MCP server 后：
curl -s -X POST localhost:8080/api/v1/tool-policy/rules -H 'Content-Type: application/json' \
  -d '{"ruleType":"GLOBAL_DENY","pattern":"<server>:*"}'
# 该 server 全部工具按 V2 口径被拒；精确名规则可单独豁免其中一个（US2 配 exempt 验证精确优先）
```

## V4 — 按 Agent 覆写（US2 / SC-003）

```bash
# 全局 deny shell + ops 例外：
curl -s -X POST .../rules -d '{"ruleType":"GLOBAL_DENY","pattern":"shell"}'
curl -s -X POST .../rules -d '{"ruleType":"AGENT_EXEMPT","agentName":"<ops-agent>","pattern":"shell"}'
# → ops-agent 可用 shell，其它 Agent 被拒
# 定向收紧：
curl -s -X POST .../rules -d '{"ruleType":"AGENT_DENY","agentName":"<ops-agent>","pattern":"shell"}'
# → 三重叠加收敛：ops-agent 也被拒（AGENT_DENY 最终收紧）；GET /api/v1/tool-policy 的 removed[].reason 可见裁决依据
```

## V5 — 热更新（US3 场景 2 / SC-004)

```bash
# 不重启：DELETE 掉 V4 的 AGENT_DENY 规则 → 下一条消息 ops-agent 立即恢复 shell
curl -s -X DELETE localhost:8080/api/v1/tool-policy/rules/<id>
```

## V6 — 管理台策略页（US3 / SC-006）

浏览器 `/admin/` → 工具策略页：三组规则增删、每个 Agent 的有效工具集与 removed 原因可见；编辑后无需重启生效；审计页按「策略拒绝」筛出 V2 的记录（SC-005）。

## V7 — 告警与边界（FR-008/FR-011）

```bash
# 未知工具名规则 → 保存成功但响应/日志告警；deny 到 Agent 全空 → Agent 纯对话运行 + 告警；
# GLOBAL_DENY 带 agentName / 重复规则 → 400 / 409
```

## 质量门禁

```bash
mvn verify   # 全量门禁 + 全部新老测试
```
