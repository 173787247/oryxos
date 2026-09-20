# Acceptance report: 042 HITL approval policy (#464)

## Automated tests

- `ConfigApprovalPolicyServiceImplTest` — default off, action type / agent+tool / MCP wildcard, Properties load, HIT+APPROVED stub
- `ApprovalInterceptTest` — prompt visibility consistency, execution stub + `blocked_by=approval`, PASS_THROUGH
- `HighRiskActionClassifierTest` — builtin + MCP classification
- `MigrationEvolutionIT` — tip migration V18 idempotent converge

## SC vs #464

| Acceptance | Status |
|------------|--------|
| Policy consistent on prompt visibility and execution | OK — same `ApprovalPolicyService` |
| Configurable by Agent / tool / action type | OK — `oryxos.approval.rules` |
| Policy hit and final decision fully audited | OK — `approval_events` + `blocked_by=approval` |

## Deferred

- #465 durable suspend / checkpoint resume (REQUIRE = stub deny in this cut)
- #466 admin + IM approval UX (`recordHumanDecision` audit-only stub)
