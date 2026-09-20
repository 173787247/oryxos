# Feature Specification: Flow human nodes, compensation, and replay

**Feature Branch**: `feat/469-flow-human-compensate-replay`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #469 (epic #456); follows #467 DSL + #468 durable engine

## Intent

Make HUMAN/APPROVAL first-class wait/timeout/cancel/resume, run declared compensation on
node failure, and expose a complete per-node timeline for run replay — on top of the durable
`FlowEngine` from #468.

## Hard constraints

- `oryxos.flow.engine-enabled` remains master switch (default **false**)
- `oryxos.flow.compensation-enabled` default **false** — compensation is opt-in
- Reuse `completeWaiting` / WAITING hooks from #468; mirror #466 expiry + cancel safety
- No Admin/IM Flow UI in this cut (library + persistence only)

## Acceptance mapping (#469)

| Acceptance | This cut |
|------------|----------|
| 人工节点可等待、超时、取消和恢复 | WAITING + `timeoutSeconds` → `expiresAt`; `cancelWaiting`; `expireWaiting`; `completeWaiting` resume |
| 失败节点按声明执行补偿 | Node `compensate: <nodeId>`; on FAILED (retries exhausted) run compensate step when flag on |
| 可查看完整节点时间线 | `FlowEngine.timeline(runId)` ordered step lifecycle events |

## Out of scope

- Admin / IM Flow trigger surfaces
- Visual timeline UI
- Full Speckit 九件套
