---
apiVersion: oryxos.flow/v1
kind: Flow
id: knowledge-handoff
version: "1.0.0"
entry: answer
permissions:
  roles: [support-agent, support-human]
  agents: [knowledge-support]
budget:
  maxDurationSeconds: 1800
  maxToolCalls: 12
nodes:
  answer:
    type: agent
    ref: knowledge-support
    inputs:
      question:
        type: string
        required: true
    outputs:
      reply:
        type: string
      escalate:
        type: string
  review:
    type: human
    timeoutSeconds: 3600
    inputs:
      ticket:
        type: string
        from: answer.escalate
        required: true
    outputs:
      resolution:
        type: string
  notify_user:
    type: notify
    ref: feishu
    inputs:
      text:
        type: string
        from: review.resolution
    outputs:
      delivered:
        type: boolean
  done:
    type: notify
    ref: feishu
    inputs:
      text:
        type: string
        from: answer.reply
    outputs:
      delivered:
        type: boolean
edges:
  - from: answer
    to: review
    when: "escalate == yes"
  - from: answer
    to: done
    when: "escalate == no"
  - from: review
    to: notify_user
---

# Knowledge handoff

知识支持标杆 Flow（#478）：Agent 先答；无依据时进入 human 节点，人工结论再通知用户。
静态校验通过即可入库；执行依赖 `oryxos.flow.engine-enabled`（默认关）。
