# Data model: Markdown Flow DSL（045 / #467）

## Document shape

```text
---
apiVersion: oryxos.flow/v1
kind: Flow
id: <flow-id>
version: "<semver-or-string>"
entry: <node-id>
permissions: { roles: [...], agents: [...] }   # optional
budget: { maxDurationSeconds, maxToolCalls, maxTokens }  # optional
nodes:
  <node-id>:
    type: agent|tool|notify|human|approval
    ref: <agent|tool|channel name>             # optional by type
    dependsOn: [<node-id>, ...]                # optional explicit deps
    inputs:
      <port>: { type: string|number|boolean|object|any, from: <node.port>, required: true|false }
    outputs:
      <port>: { type: ... }
edges:
  - from: <node-id>
    to: <node-id>
    when: <optional branch predicate string>
---

# Human-readable title / narrative (Git review surface)
```

## Port wire

`from: <nodeId>.<portName>` binds an input to another node's output. Types must be compatible
(`any` is a wildcard). Missing node/port yields `UNKNOWN_NODE_REF` / `UNKNOWN_PORT_REF`.

## Graph edges for cycle detection

Union of:

1. Explicit `edges[].from` to `to`
2. `dependsOn` edges
3. Implicit edges from `inputs.*.from` (producer node to consumer node)

## Diagnostics (static)

| Code | Meaning |
|------|---------|
| INVALID_API_VERSION | missing/unsupported `apiVersion` |
| MISSING_ID / MISSING_ENTRY | required identity fields |
| DUPLICATE_NODE | duplicate node id (defensive) |
| UNKNOWN_ENTRY | `entry` not in `nodes` |
| UNKNOWN_NODE_REF | edge / dependsOn / wire target missing |
| UNKNOWN_PORT_REF | `from` port missing on producer |
| TYPE_MISMATCH | wired port types incompatible |
| CYCLE_DETECTED | dependency cycle |
| EMPTY_NODES | no nodes declared |
