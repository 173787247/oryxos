# Routing API (#477)

Base: `/api/v1/routing` → **404** when `oryxos.routing.enabled=false`.

## GET /decisions

Query params:

- `runId` (optional) — filter by run/trace id
- `limit` (optional, default 20, max 100) — recent decisions when `runId` omitted

Returns `{ decisions: [ ... ] }` with reasons, candidate dispositions, and attemptOrder.
