# Feature Specification: Admin + IM approval interaction

**Feature Branch**: `feat/466-approval-interaction`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #466 (epic #455); follows #464 / #465

## Intent

Provide Admin console + Feishu/WeCom thin callback entry and a unified decide path that resumes durable checkpoints via `DurableTaskReplay`, with expiry and duplicate-callback safety.

## Hard constraints

- `oryxos.approval.interaction-api-enabled` default **false** → `/api/v1/approvals/**` 404
- Requires underlying durable checkpoints (#465); resume always goes through `DurableTaskReplay.resume`
- IM channels are **thin stubs** (no card send / signature verify); same JSON callback contract for `feishu` / `wecom`
- Orthogonal to 020 tool policy and 039 authz (admin paths → `MANAGE_POLICIES`; callbacks skip like inbound)

## Acceptance mapping (#466)

| Acceptance | This cut |
|------------|----------|
| approve/deny/修改参数可用 | `POST /api/v1/approvals/{id}/decide` + Admin UI；可选 `argumentsJson` |
| 审批人身份、意见、时效和执行结果可回放 | `GET` detail + `approval_events`（APPROVED/DENIED/TIMEOUT_DENIED）+ checkpoint state/lastError/ttl/expiresAt |
| 过期与重复回调安全处理 | expire → TIMEOUT_DENIED + CANCELLED；`callbackId` 收据表去重；终态 decide 幂等 |

## Out of scope

- Real Feishu/WeCom card push & signature verification
- Mid-ReAct auto-continue after tool replay (same as #465)
