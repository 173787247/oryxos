# Feature Specification: Explainable model routing & cost strategy (#477)

**Feature Branch**: `feat/477-explainable-model-routing`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #477 (epic #459); follows #476 cost ledger

## Intent

On top of Day-One Provider fallback (023) and task cost ledger (#476), add an **explainable**
model routing strategy that can reorder/filter the Agent's declared candidate list using task
difficulty, data sensitivity (residency), budget signals, and latency/cost preferences — without
expanding privileges beyond the Agent allowlist.

## Hard constraints

- `oryxos.routing.enabled` default **false** (zero runtime behavior change; gray-release / rollback)
- Reuse #476 `CostLedgerService.checkBudget` + `llm_pricing` for cost signals
- Do **not** route to providers outside Agent primary+fallbacks (no privilege expansion)
- Residency filter only removes candidates; never invents new ones

## Acceptance mapping (#477)

| Acceptance | Coverage |
|------------|----------|
| 路由和 fallback 原因可追溯 | `RoutingDecision` + `GET /api/v1/routing/decisions`; `recordFallback` on 023 switch |
| 策略可灰度并回滚 | `oryxos.routing.enabled` default false; flip on/off |
| 不突破权限和数据驻留约束 | Allowlist-only promote; residency filter; outside prefs ignored |

## Out of scope

- Admin UI policy editor
- Cross-request health memory / circuit breaker
- Auto-expanding provider catalog beyond Agent declaration
