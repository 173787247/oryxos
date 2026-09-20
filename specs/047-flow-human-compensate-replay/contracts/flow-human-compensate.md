# Contract: Flow human / compensate / timeline（047 / #469）

## Flags

| Property | Default | Effect |
|----------|---------|--------|
| `oryxos.flow.engine-enabled` | `false` | Master switch (unchanged from #468) |
| `oryxos.flow.compensation-enabled` | `false` | When false, node failures do not run `compensate` |

## API additions (`FlowEngine`)

1. `cancelWaiting(runId, reason)` — WAITING → CANCELLED (run + step); idempotent on terminal
2. `expireWaiting(runId)` — if WAITING step `expiresAt` ≤ now → CANCELLED with timeout error
3. `completeWaiting(runId, outputs)` — rejects expired waits (same safety as #466 decide)
4. `timeline(runId)` → `List<FlowTimelineEvent>` — full node timeline for replay
5. On FAILED after retries: if compensation enabled and node declares `compensate`, execute that node once

## DSL

```yaml
nodes:
  review:
    type: human
    timeoutSeconds: 3600
    outputs:
      decision: { type: string }
  charge:
    type: tool
    compensate: refund
```
