---
apiVersion: oryxos.flow/v1
kind: Flow
id: branch-approve
version: "1.0.0"
entry: prepare
permissions:
  roles: [operator, approver]
  agents: [preparer]
budget:
  maxDurationSeconds: 3600
  maxTokens: 50000
nodes:
  prepare:
    type: agent
    ref: preparer
    inputs:
      change:
        type: string
        required: true
    outputs:
      plan:
        type: string
  review:
    type: human
    inputs:
      plan:
        type: string
        from: prepare.plan
        required: true
    outputs:
      decision:
        type: string
  apply:
    type: tool
    ref: shell.exec
    dependsOn: [review]
    inputs:
      plan:
        type: string
        from: prepare.plan
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
edges:
  - from: prepare
    to: review
  - from: review
    to: apply
    when: "decision == approved"
  - from: review
    to: abort
    when: "decision == denied"
---

# Branch approve

Branching Flow with a human review node (schema-only in this cut).
`when` predicates are preserved for later engine cuts; static validation checks graph integrity only.
