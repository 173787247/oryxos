---
apiVersion: oryxos.flow/v1
kind: Flow
id: human-timeout
version: "1.0.0"
entry: ask
nodes:
  ask:
    type: human
    timeoutSeconds: 60
    outputs:
      answer:
        type: string
  done:
    type: notify
    ref: feishu
    inputs:
      text:
        type: string
        from: ask.answer
    outputs:
      delivered:
        type: boolean
edges:
  - from: ask
    to: done
---

# Human timeout demo
