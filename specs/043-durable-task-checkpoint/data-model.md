# Data model: durable_task_checkpoints（V19）

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(64) PK | checkpoint id |
| execution_id | BIGINT | nullable link to agent_executions |
| session_id | VARCHAR(128) | |
| agent_name | VARCHAR(255) | |
| state | VARCHAR(32) | DurableTaskState |
| idempotency_key | VARCHAR(255) UNIQUE | retry key |
| checkpoint_kind | VARCHAR(64) | PRE_TOOL_APPROVAL |
| tool_name / tool_call_id / arguments_json | | pending tool |
| policy_version / rule_id | | approval context |
| attempt | INT | |
| last_error | VARCHAR(1024) | |
| created_at / updated_at | TIMESTAMPTZ | |

`agent_executions.status` 增加非终态 `WAITING_APPROVAL`（无新列）。
