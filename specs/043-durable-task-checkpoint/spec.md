# Feature Specification: Durable task state machine + checkpoint resume

**Feature Branch**: `feat/465-durable-task-checkpoint`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #465 (epic #455); follows #464

## Intent

Model runtime work as a durable state machine with checkpoint persistence so high-risk actions can suspend on `REQUIRE_APPROVAL`, survive process restart, resume after human decision, and retry with idempotency keys.

## Hard constraints

- `oryxos.approval.durable-suspend` default **false** — when off, #464 stub deny behavior unchanged
- Durable suspend only arms when `oryxos.approval.enabled=true` **and** `durable-suspend=true`
- Admin / IM approval UX deferred to #466 (`DurableTaskReplay` is the programmatic resume entry)
- Orthogonal to 020 tool policy and 039 authz

## Acceptance mapping (#465)

| Acceptance | This cut |
|------------|----------|
| 实例重启后状态不丢失 | `durable_task_checkpoints` (V19) + `agent_executions.status=WAITING_APPROVAL` not reconciled to FAILED |
| 重试具备幂等键 | `idempotency_key` unique; `retryWithIdempotencyKey` / re-suspend returns same checkpoint |
| 可从最近安全检查点恢复并回放 | `DurableTaskReplay.resume` → grant → `ToolExecutor` executes pending tool → SUCCEEDED/FAILED |

## States

`QUEUED` → `RUNNING` → `WAITING_APPROVAL` → (`RUNNING` → `SUCCEEDED`\|`FAILED`) \| `CANCELLED`

## Out of scope

- Admin console / Feishu / WeCom approval UI (#466)
- Full mid-ReAct loop auto-continue after tool replay (caller may re-trigger Agent turn)
- Argument-level policy matchers
