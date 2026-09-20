# Contract: Durable Flow engine（046 / #468）

## Flag

`oryxos.flow.engine-enabled` (default `false`). When false, `FlowEngine.start/resume/completeWaiting`
throw `IllegalStateException`.

## API (library)

1. `FlowEngine.start(markdown|definition, inputs)` — validate, persist run, advance until terminal or WAITING
2. `FlowEngine.resume(runId)` — continue RUNNING after restart; no-op on WAITING/terminal
3. `FlowEngine.completeWaiting(runId, outputs)` — finish HUMAN/APPROVAL step, continue
4. `findRun` / `listSteps` / `listWaiting` — query surface

## Node execution

`FlowNodeHandler` SPI. Default handler: HUMAN/APPROVAL → WAITING; AGENT/TOOL/NOTIFY echo/stub outputs
(pluggable per-node scripts). Branch `when` via `FlowBranchPredicates` (`port == literal`).

## Idempotency

On advance, if a step with `idempotency_key=flow:{runId}:{nodeId}` is already `SUCCEEDED`, skip
handler and walk edges.
