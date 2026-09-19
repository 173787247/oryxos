# Data model: approval_events（V18）

追加型审计表，策略命中与最终决策分条写入。

| Column | Type | Notes |
|--------|------|-------|
| id | PK | |
| kind | VARCHAR(32) | HIT_REQUIRE / HIT_DENY / APPROVED / DENIED / TIMEOUT_DENIED |
| session_id | VARCHAR(128) | nullable |
| agent_name | VARCHAR(255) | |
| tool_name | VARCHAR(255) | |
| action_type | VARCHAR(64) | HighRiskActionType name |
| policy_version | VARCHAR(64) | |
| rule_id | VARCHAR(128) | |
| actor | VARCHAR(128) | system / approver id |
| reason | VARCHAR(1024) | |
| ttl_seconds | INTEGER | nullable |
| created_at | TIMESTAMP | |

执行闸同步写 `tool_invocations.blocked_by='approval'`（与 `policy` / `authz` 可区分筛选）。
