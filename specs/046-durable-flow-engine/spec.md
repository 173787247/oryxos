# Feature Specification: Durable Flow execution engine

**Feature Branch**: `feat/468-durable-flow-engine`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #468 (epic #456); follows #467 Markdown Flow DSL

## Intent

Execute validated Markdown Flows with durable run/step persistence so Agent/Tool/Notify/Approval
graphs can pause, survive process restart, resume without re-running SUCCEEDED idempotent nodes,
and expose per-node results/errors for query.

## Hard constraints

- `oryxos.flow.engine-enabled` default **false** — zero behavior change when off
- Reuse `io.oryxos.core.flow` DSL + `FlowValidator` from #467 (fail closed on ERROR diagnostics)
- HUMAN/APPROVAL: minimal WAITING hook via `FlowEngine.completeWaiting` — full HITL / compensate /
  timeline UI deferred to #469
- Persistence: core `FlowRunStore` + in-memory; JPA/Flyway V21 in `oryxos-storage`

## Acceptance mapping (#468)

| Acceptance | This cut |
|------------|----------|
| 支持中断后恢复 | Persist `flow_runs` / `flow_steps`; `resume` after restart; WAITING survives reload |
| 节点结果与错误可查询 | `listSteps` / step `outputsJson` + `error` + run `lastError` |
| 不重复执行已成功的幂等节点 | Step `idempotency_key=flow:{runId}:{nodeId}`; SUCCEEDED steps skipped on advance/resume |

## Out of scope

- Human wait timeout/cancel UX, compensation, visual replay (#469)
- Admin / IM Flow triggers
- Full Speckit 九件套
