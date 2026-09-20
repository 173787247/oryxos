# Skill: 请求人工审批

触发条件：分诊结果 `needs_approval: yes`，或拟执行任何会改变工单/资产状态的动作。

输出（纯文本）：

```text
APPROVAL_REQUIRED
ticket_id: <id>
proposed_change: <草案>
risk: <low|medium|high>
reason: critical_write | policy_gate | high_risk
```

然后停止。由 Flow `ticket-approve-apply` 将摘要交给 `review` human 节点。

禁止：

- 调用 `ticket.apply_change` / `ticket.rollback`
- 在未获 `decision == approved` 前声称「已执行」
