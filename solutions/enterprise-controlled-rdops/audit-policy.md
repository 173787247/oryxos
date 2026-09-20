# 受控研发运维审计策略（#480）

## 原则

1. **默认无危险写**：Agent 与 Tool Policy 均不得放行 `shell` / `exec` / `write_file` / `ops.exec` / `ops.deploy`。
2. **批准参数唯一真源**：人工节点输出 `approved_params`；执行节点只消费该字段，忽略 Agent 草案中的未批准参数。
3. **安全可审计**：每次 HIT / APPROVED / DENIED / 执行结果写入审批审计或 Flow 步骤时间线。
4. **成本可审计**：评测与运行记录 `costMicros`（LLM + 工具）；可与任务级成本账本对账（050 / #476，默认关）。
5. **结果可审计**：`apply.result` 与 timeline 保留成功/失败与参数摘要，供排障回放。

## Flow 约定

```text
prepare (agent) → review (human: decision + approved_params)
                      ├─ approved → apply (ops.exec, params=approved_params) → done
                      └─ denied   → abort (notify, 无执行)
```

## 审计字段（样例）

| 维度 | 字段 | 来源 |
|------|------|------|
| 安全 | decision, actor, approved_params | human 节点 / ApprovalAuditRecorder |
| 成本 | costMicros, latencyMs | eval suite / CostLedger（可选） |
| 结果 | apply.result, step.state | Flow steps / timeline |

## 禁止

- 在未获 `decision == approved` 前调用 `ops.exec`
- 将 Agent `plan` 直接作为执行参数（绕过批准参数绑定）
- 在审计关闭时声称「已执行」却无可回放证据
