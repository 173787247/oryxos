# Contract: Approval interaction（044 / #466）

## Admin API（flag on）

| Method | Path | Behavior |
|--------|------|----------|
| GET | `/api/v1/approvals` | list `WAITING_APPROVAL` (+ expired flag) |
| GET | `/api/v1/approvals/{checkpointId}` | detail for replay (state/ttl/expires/lastError/args) |
| POST | `/api/v1/approvals/{checkpointId}/decide` | `{approved, actor, comment, argumentsJson?}` → `DurableTaskReplay.resume` |

## IM unified callback（thin stub）

`POST /api/v1/approvals/callbacks/{channel}` where `channel` ∈ `feishu` \| `wecom`

```json
{
  "callbackId": "unique-per-channel",
  "checkpointId": "cp-…",
  "approved": true,
  "actor": "open_id_or_userid",
  "comment": "optional",
  "argumentsJson": "optional"
}
```

Duplicate `callbackId` → `{duplicate:true}` no second tool exec.

## Expiry

If `expiresAt <= now` while still `WAITING_APPROVAL`: cancel, audit `TIMEOUT_DENIED`, do not execute tool.
