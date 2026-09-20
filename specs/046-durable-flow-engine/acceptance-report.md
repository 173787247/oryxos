# Acceptance report: #468 durable Flow execution engine

| Criterion | Evidence |
|-----------|----------|
| 中断后恢复 | `FlowEngineTest.restart_survivesStoreReload`; `interrupt_resume_skipsSucceededIdempotentNodes`; V21 tables |
| 节点结果与错误可查询 | `linear_persistsStepResults`; `nodeFailure_errorQueryable` |
| 不重复执行已成功幂等节点 | `interrupt_resume_skipsSucceededIdempotentNodes` (draftCalls stays 1) |
| Default-off | `disabled_rejectsStart`; `oryxos.flow.engine-enabled: false` |
| Approval WAITING hook | `approval_waiting_thenComplete` (full HITL = #469) |
