# Acceptance report: 050-cost-ledger (#476)

**Date**: 2026-09-20  
**Verdict**: Thin cut covers #476 acceptance; routing left to #477.

| Acceptance | Evidence |
|------------|----------|
| ???????????? | `CostLedgerServiceTest.attributionAndPriceVersion`; `CostApiControllerTest.attributionWhenEnabled` |
| ????????????? | `CostLedgerServiceTest.overBudgetBlocks` / `overBudgetDegrades`; provider `applyBudgetGate` |
| ?????????? | `CostLedgerServiceTest.reconcileMatchesAudit` + `/runs/{runId}/reconcile` |
| Default-off | `oryxos.cost.enabled=false`; APIs 404; `flagOffIsNoop` |
