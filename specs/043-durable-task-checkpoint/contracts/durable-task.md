# Contract: Durable task + checkpoint（043 / #465）

## Suspend

`ToolExecutor` on `REQUIRE_APPROVAL` when durable armed:

1. `approval_events` HIT_REQUIRE (unchanged)
2. Persist checkpoint `WAITING_APPROVAL`
3. Throw `ApprovalSuspendedException`
4. `AgentExecutionService` marks run `WAITING_APPROVAL` (non-terminal)

## Resume / replay

`DurableTaskReplay.resume(checkpointId, approved, actor, comment)`:

- deny → `CANCELLED` + human decision audit; no tool exec
- approve → `RUNNING` → `ApprovalGrantContext` → execute pending tool → `SUCCEEDED`/`FAILED`
- terminal checkpoints are idempotent no-ops

## Idempotency

Key format: `exec:{id}|session:{sid}|toolCall:{tc}|attempt:{n}`

Re-suspend / `retryWithIdempotencyKey` with same key returns the existing checkpoint.
