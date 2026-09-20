# Data model: flow_runs / flow_steps（V21 / #468）

## flow_runs

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(64) PK | run id (`fr-…`) |
| flow_id / flow_version | | from definition |
| definition_markdown | TEXT | source snapshot for resume |
| state | VARCHAR(32) | FlowRunState |
| entry_node_id / current_node_id | | cursor |
| inputs_json / context_json | TEXT | run inputs; accumulated `node.port` outputs |
| last_error | VARCHAR(1024) | |
| attempt | INT | |
| created_at / updated_at | TIMESTAMPTZ | |

## flow_steps

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(64) PK | |
| run_id | VARCHAR(64) | |
| node_id / node_type | | |
| state | VARCHAR(32) | FlowStepState |
| attempt | INT | |
| idempotency_key | VARCHAR(255) UNIQUE | `flow:{runId}:{nodeId}` |
| inputs_json / outputs_json | TEXT | |
| error | VARCHAR(1024) | |
| started_at / finished_at | | |
| created_at / updated_at | | |

## States

Run: `QUEUED` → `RUNNING` → (`WAITING` → `RUNNING`) → `SUCCEEDED`\|`FAILED`\|`CANCELLED`

Step: `PENDING` → `RUNNING` → `SUCCEEDED`\|`FAILED`\|`WAITING`\|`SKIPPED`
