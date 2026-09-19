# Feature Specification: High-risk approval policy contract (HITL)

**Feature Branch**: `feat/464-high-risk-approval-policy`

**Created**: 2026-09-19

**Status**: First cut (thin stub — not full 九件套)

**Tracks**: #464 (epic #455)

## Intent

Define which Tool / MCP / action types require human approval, plus policy version, approvers, TTL, and deny semantics. Prompt visibility and execution share one decision point; policy hits and final decisions are fully audited.

This cut is the HITL epic contract layer: classification + config + execution gate stub. It does **not** durable suspend/resume (#465) or admin/IM approval UX (#466).

## Hard constraints

- `oryxos.approval.enabled` default **false** — zero behavior change when off
- Orthogonal to 020 `ToolPolicyService`: tool policy first, then approval; neither exempts the other
- Prompt and execution must inject the **same** `ApprovalPolicyService` implementation
- Visibility: `DENY` hides tools; `REQUIRE_APPROVAL` stays visible; execution gates both
- This cut's `REQUIRE_APPROVAL` execution stub = block action + `blocked_by='approval'` + `approval_events` HIT; true suspend is #465

## Acceptance mapping (#464)

| Acceptance | This cut |
|------------|----------|
| Policy consistent on prompt visibility and execution | `PromptBuilder` + `ToolExecutor` share `ApprovalPolicyService.evaluate` |
| Configurable by Agent / tool / action type | `oryxos.approval.rules[]`: `agents` / `tools` (incl. `server:*`) / `action-types` |
| Policy hit and final decision fully audited | `approval_events` (HIT_* / APPROVED / DENIED) + `blocked_by=approval` |

## Out of scope

- Durable task state machine / checkpoint resume (#465)
- Admin + IM approval interaction (#466)
- Argument-level matchers (args passed through; classification is tool/action-type based)
- Full research/plan/tasks suite

## Key types

- `HighRiskActionType` / `ApprovalOutcome` / `ApprovalPolicyDecision`
- `ApprovalPolicyService` + `ConfigApprovalPolicyServiceImpl`
- `ApprovalAuditRecorder` -> `approval_events` (V18)
- `ApprovalHumanDecision` — approve/deny stub (audit only, no resume)
