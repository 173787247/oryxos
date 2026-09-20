---
apiVersion: oryxos.flow/v1
kind: Flow
id: charge-refund
version: "1.0.0"
entry: charge
nodes:
  charge:
    type: tool
    ref: payment.charge
    compensate: refund
    outputs:
      result:
        type: string
  refund:
    type: tool
    ref: payment.refund
    outputs:
      result:
        type: string
edges: []
---

# Charge with compensate refund
