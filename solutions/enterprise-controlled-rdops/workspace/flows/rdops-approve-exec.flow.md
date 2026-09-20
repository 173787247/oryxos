---
apiVersion: oryxos.flow/v1
kind: Flow
id: rdops-approve-exec
version: "1.0.0"
entry: prepare
permissions:
  roles: [rdops-agent, rdops-approver]
  agents: [rdops-controlled]
budget:
  maxDurationSeconds: 3600
  maxToolCalls: 16
nodes:
  prepare:
    type: agent
    ref: rdops-controlled
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
        from: prepare.plan
        required: true
    outputs:
      decision:
        type: string
      approved_params:
        type: string
  apply:
    type: tool
    ref: ops.exec
    dependsOn: [review]
    inputs:
      params:
        type: string
        from: review.approved_params
        required: true
    outputs:
      result:
        type: string
  abort:
    type: notify
    ref: feishu
    inputs:
      text:
        type: string
        from: prepare.plan
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
  - from: prepare
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

# RdOps approve exec

受控研发运维标杆 Flow（#480）：Agent 起草 → human 审批并给出 `approved_params` → 仅 approved 后执行 `ops.exec`，且参数只取自批准字段。
静态校验通过即可入库；执行依赖 `oryxos.flow.engine-enabled`（默认关）。
