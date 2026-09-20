# #479 验收映射

Parent: #460

## 范围对照

| 范围项 | 本包落点 |
|--------|----------|
| 工单/运营流程样例 | `workspace/agents/ticket-ops/` + `workspace/flows/ticket-approve-apply.flow.md` |
| 审批（HITL） | Flow `review` human 节点 + `approval-policy.md` |
| 失败可恢复 / 无重复副作用 | `apply.compensate: rollback` + 引擎幂等键；评测/引擎用例 |
| 端到端回放 | `FlowEngine.timeline(runId)`；用例 `timeline-replay` |
| 权限样例 | `permissions.sample.yml` |
| 评测集 | `workspace/evals/process-ticket-suite.json`（CI 镜像见 oryxos-core test resources） |
| 部署验收 | 本文 + `scripts/accept.sh` + README 快速落地 |

## 验收标准

### 1. 关键动作必须审批

- 工单写操作不得由 Agent 直接调用；须经 Flow `ticket-approve-apply` 的 `review`（human）节点
- `decision == approved` 才进入 `apply`；`denied` 走 `abort`，不产生写副作用
- 评测用例 `approval-gate` / `denied-no-side-effect` 必须成功

**怎么验**

```bash
./solutions/enterprise-process-ticket/scripts/accept.sh
# 或
mvn -pl oryxos-core -Dtest=EnterpriseProcessTicketPackTest,FlowDocumentsTest test
```

### 2. 失败可恢复且不重复产生副作用

- `apply` 声明 `compensate: rollback`；失败且 `compensation-enabled` 时执行一次补偿
- 中断恢复时已成功节点按幂等键跳过，不重跑副作用（与 046 / #468 一致）
- 评测用例 `compensate-once` / `idempotent-resume` 必须成功

### 3. 可回放端到端执行链路

- `FlowEngine.timeline(runId)` 返回按节点顺序的完整时间线
- 评测用例 `timeline-replay` 与引擎断言覆盖 triage → review → apply

### 4. 新环境可按文档独立验收

干净 clone → 不配真实 API Key → `scripts/accept.sh` exit 0 即可完成离线门禁。

## 非目标

- #478 知识引用与支持转人工（已交付）
- #480 受控研发运维危险写权限
- 打开 `oryxos.flow.engine-enabled` / `compensation-enabled` 默认值（保持 false）
