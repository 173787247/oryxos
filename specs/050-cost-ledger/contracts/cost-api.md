# Cost API (#476)

Base: `/api/v1/cost` ? **404** when `oryxos.cost.enabled=false`.

## GET /attribution

Query params (all optional): `runId`, `taskId`, `agent`, `teamId`, `provider`, `model`.

Returns totals + `priceVersions[]` + `entries[]`.

## GET /runs/{runId}/reconcile

Compares ledger LLM micros for `runId` to sum(`llm_calls.cost_micros` where `trace_id=runId`).
Tool costs are ledger-only and reported separately.
