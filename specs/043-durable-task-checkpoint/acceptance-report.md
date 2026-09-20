# Acceptance report: #465 durable task checkpoint

| Criterion | Evidence |
|-----------|----------|
| Restart keeps state | `DurableTaskServiceTest.suspend_survivesStoreReload`; V19 table; reconcile skips WAITING_APPROVAL |
| Idempotent retry key | `retry_idempotentKey_returnsSameCheckpoint` |
| Resume + replay from checkpoint | `resume_fromCheckpoint_replaysTool_idempotent`; `DurableSuspendInterceptTest` |

Covered by #466 (044-approval-interaction).
