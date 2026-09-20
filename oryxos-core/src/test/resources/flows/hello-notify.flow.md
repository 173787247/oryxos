---
apiVersion: oryxos.flow/v1
kind: Flow
id: hello-notify
version: "1.0.0"
entry: draft
permissions:
  roles: [operator]
budget:
  maxDurationSeconds: 600
  maxToolCalls: 8
nodes:
  draft:
    type: agent
    ref: writer
    inputs:
      topic:
        type: string
        required: true
    outputs:
      message:
        type: string
  send:
    type: notify
    ref: feishu
    inputs:
      text:
        type: string
        from: draft.message
        required: true
    outputs:
      delivered:
        type: boolean
edges:
  - from: draft
    to: send
---

# Hello notify

Minimal linear Flow: an agent drafts a message, then a notify node delivers it.
Static lint must pass with zero ERROR diagnostics.
