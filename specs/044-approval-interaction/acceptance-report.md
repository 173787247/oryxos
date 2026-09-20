# Acceptance report: #466 approval interaction

| Criterion | Evidence |
|-----------|----------|
| approve/deny/改参 | `ApprovalInteractionServiceTest.approve_withEditedArgs_replays`; Admin UI + `POST …/decide` |
| 身份/意见/时效/结果可回放 | detail view + `approval_events` + checkpoint `ttlSeconds`/`expiresAt`/`state`/`lastError` |
| 过期安全 | `expired_decide_timeoutDenied` → TIMEOUT_DENIED + CANCELLED |
| 重复回调安全 | `duplicateCallback_safe` + `approval_callback_receipts` |
| Default-off | `oryxos.approval.interaction-api-enabled=false` → 404 (`ApprovalInteractionApiControllerTest`) |

IM: Feishu/WeCom share unified callback stub; card push /签名验签 follow-up.
