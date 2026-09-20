# Contract: Approval Policy（042 / #464）

## 1. Decision point

唯一裁决接口：`io.oryxos.core.policy.ApprovalPolicyService#evaluate(agent, tool, argumentsJson)`。

| Outcome | Prompt | Execution (this cut) | Audit |
|---------|--------|----------------------|-------|
| ALLOW | visible | execute | none |
| REQUIRE_APPROVAL | visible | block stub（零执行） | `approval_events` HIT_REQUIRE + `blocked_by=approval` |
| DENY | hidden | block | `approval_events` HIT_DENY + `blocked_by=approval`（若仍被调用） |

Human final decision stub：`recordHumanDecision(ApprovalHumanDecision)` → `APPROVED` / `DENIED` 行；**不**恢复执行。

## 2. Orthogonality

```
Tool Policy (020) → Approval (042) → Sandbox → tool.execute
```

## 3. Classification

`HighRiskActionClassifier` maps builtins → `SHELL` / `EXTERNAL_SEND` / `FILE_MUTATION`；MCP ownership → `MCP`。

## 4. Schema fields (rule)

- `id`, `agents`, `tools`, `action-types`, `approvers`, `ttl-seconds`
- `effect`: `require_approval` | `deny`
- `on-timeout` / `on-deny`: deny semantics（契约字段；耐久等待落地后由 #465/#466 落实超时路径）

## 5. Observability

- Span 预留：`SpanRecorder.recordApprovalSpan(traceId, policyVersion, decision, …)`
- 命名建议：`oryxos.approval`（见 `docs/ObservabilityModel.md` §5）
