# Acceptance report: #469 Flow human / compensate / replay

| Criterion | Evidence |
|-----------|----------|
| 人工等待 / 恢复 | `FlowHumanCompensateReplayTest.human_waiting_thenComplete` |
| 人工超时 | `human_timeout_expiresWaiting` |
| 人工取消 | `human_cancelWaiting` |
| 过期后拒绝恢复 | `human_expired_rejectsComplete` |
| 失败补偿（flag on） | `compensation_onFailure_runsDeclaredNode` |
| 补偿默认关 | `compensation_disabled_skipsCompensate` |
| 完整节点时间线 | `timeline_listsOrderedNodeEvents` |
| Default-off | `engine-enabled` + `compensation-enabled` false in `application.yml` |
| V22 expires_at | PG/SQLite migration; Flyway history 18 |
