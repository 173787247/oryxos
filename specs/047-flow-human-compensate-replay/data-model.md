# Data model: human wait + compensation (#469 / 047)

## DSL (`FlowNode`)

| Field | Type | Notes |
|-------|------|-------|
| `timeoutSeconds` | int? | HUMAN/APPROVAL wait TTL; null = no auto-expire |
| `compensate` | string? | Node id executed when this node fails (retries exhausted) |

## Persistence (V22)

`flow_steps.expires_at` TIMESTAMPTZ NULL — set when step enters WAITING with a timeout.

## Step states (additive)

| State | Meaning |
|-------|---------|
| `CANCELLED` | Waiting step cancelled by caller or timeout |
| (existing) `WAITING` / `SUCCEEDED` / `FAILED` / … | unchanged |

Compensate runs are normal steps with idempotency key
`flow:{runId}:{failedNodeId}:compensate` and outputs carrying `__compensatesFor`.

## Timeline

Derived from `listSteps` — no extra table. Each step yields one or more
`FlowTimelineEvent` rows ordered by `at` (started → finished).
