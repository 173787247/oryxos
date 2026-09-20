# #480 验收映射

Parent: #460

## 范围对照

| 范围项 | 本包落点 |
|--------|----------|
| 受限工具 / 最小权限 | `permissions.sample.yml` + Agent `tools` 白名单 |
| 审批后仅执行批准参数 | Flow `rdops-approve-exec`：`apply` ← `review.approved_params` |
| 安全 / 成本 / 结果可审计 | `audit-policy.md` + 时间线 + 评测成本字段 |
| 研发运维样例 | `workspace/agents/rdops-controlled/` + playbooks |
| 评测集 | `workspace/evals/controlled-rdops-suite.json`（CI 镜像见 oryxos-core test resources） |
| 部署验收 | 本文 + `scripts/accept.sh` + README 快速落地 |

## 验收标准

### 1. 默认无危险写权限

- Agent 不得声明 `shell` / `exec` / `write_file` / `ops.exec` / `ops.deploy`
- 权限样例 GLOBAL_DENY：`shell` / `exec` / `write_file`；AGENT_DENY：`ops.exec` / `ops.deploy`
- 评测用例 `default-no-dangerous-write` 必须成功

**怎么验**

```bash
./solutions/enterprise-controlled-rdops/scripts/accept.sh
# 或
mvn -pl oryxos-core -Dtest=EnterpriseControlledRdopsPackTest,FlowDocumentsTest test
```

### 2. 被审批后仅执行批准参数

- 危险执行必须经 Flow `rdops-approve-exec` 的 `review`（human）节点
- `decision == approved` 才进入 `apply`；`denied` 走 `abort`，不产生执行副作用
- `apply` 输入绑定 `from: review.approved_params`，**不得**绑定 Agent 原始 `plan` 中的未批准命令
- 评测用例 `approved-params-only` / `denied-no-exec` 必须成功

### 3. 安全、成本、结果均可审计

- 审批决策与执行结果可通过 `FlowEngine.timeline(runId)` / steps 回放
- 评测集记录 `costMicros`；用例 `audit-security-cost-result` 覆盖安全门禁、成本与结果可观测
- `audit-policy.md` 约定审计字段与保留策略（样例，不改默认开关）

### 4. 新环境可按文档独立验收

干净 clone → 不配真实 API Key → `scripts/accept.sh` exit 0 即可完成离线门禁。

## 非目标

- #478 知识引用与支持转人工（已交付）
- #479 工单审批补偿与幂等回放（已交付）
- 打开 `oryxos.flow.engine-enabled` / `oryxos.approval.enabled` 默认值（保持 false）
