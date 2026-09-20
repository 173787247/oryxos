---
apiVersion: oryxos.flow/v1
kind: Flow
id: ticket-approve-apply
version: "1.0.0"
entry: triage
permissions:
  roles: [ticket-agent, ticket-approver]
  agents: [ticket-ops]
budget:
  maxDurationSeconds: 3600
  maxToolCalls: 16
nodes:
  triage:
    type: agent
    ref: ticket-ops
    inputs:
      change:
        type: string
        required: true
    outputs:
      plan:
        type: string
  review:
    type: human
    timeoutSeconds: 3600
    inputs:
      plan:
        type: string
        from: triage.plan
        required: true
    outputs:
      decision:
        type: string
  apply:
    type: tool
    ref: ticket.apply_change
    compensate: rollback
    dependsOn: [review]
    inputs:
      plan:
        type: string
        from: triage.plan
    outputs:
      result:
        type: string
  rollback:
    type: tool
    ref: ticket.rollback
    outputs:
      result:
        type: string
  abort:
    type: notify
    ref: feishu
    inputs:
      text:
        type: string
        from: triage.plan
    outputs:
      delivered:
        type: boolean
  done:
    type: notify
    ref: feishu
    inputs:
      text:
        type: string
        from: apply.result
    outputs:
      delivered:
        type: boolean
edges:
  - from: triage
    to: review
  - from: review
    to: apply
    when: "decision == approved"
  - from: review
    to: abort
    when: "decision == denied"
  - from: apply
    to: done
---

# Ticket approve apply

业务流程与工单标杆 Flow（#479）：Agent 分诊 → human 审批 → 仅 approved 后执行 `ticket.apply_change`；失败时补偿 `rollback`；时间线可回放。
静态校验通过即可入库；执行依赖 `oryxos.flow.engine-enabled`（默认关），补偿依赖 `oryxos.flow.compensation-enabled`（默认关）。
