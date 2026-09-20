# 工单关键动作审批与补偿策略（#479）

## 原则

1. **写前必批**：任何会改变工单/资产状态的动作（`ticket.apply_change`）必须落在 Flow `human`/`approval` 节点之后。
2. **Agent 只分诊**：`ticket-ops` 只产出摘要与变更草案，不持有写工具。
3. **一次补偿**：失败节点声明 `compensate`；启用补偿后只执行一次，避免重复回滚。
4. **幂等恢复**：已 `SUCCEEDED` 节点按幂等键跳过，中断恢复不重复副作用。
5. **可回放**：每次 run 保留节点时间线，供审计与排障回放。

## Flow 约定

```text
triage (agent) → review (human) → apply (tool, compensate=rollback) | abort (notify)
                                         └─ on failure → rollback (tool, once)
```

## 审批输出

人工节点必须给出明确决策：

| decision | 下一节点 |
|----------|----------|
| `approved` | `apply` |
| `denied` | `abort`（无写操作） |

其它值视为拒绝推进写路径。
