# 受控研发与运维 Agent 标杆方案

Tracks: **#480**（Epic **#460**）。本目录是可复制的样例包：受控研发运维 Agent、默认无危险写权限、审批后仅执行批准参数、安全/成本/结果审计、评测集与部署验收——**不改默认运行时行为**。

## 包内容

| 路径 | 用途 |
|------|------|
| [ACCEPTANCE.md](ACCEPTANCE.md) | #480 验收映射与独立验收清单 |
| [audit-policy.md](audit-policy.md) | 安全、成本、结果审计策略 |
| [permissions.sample.yml](permissions.sample.yml) | 研发运维场景工具权限样例（默认 deny 危险写） |
| [workspace/](workspace/) | 可直接拷入工作区的样例 |
| [scripts/accept.sh](scripts/accept.sh) | 新环境离线验收（无 LLM Key） |

## 快速落地

```bash
cp -a solutions/enterprise-controlled-rdops/workspace/. <your-workspace>/.oryxos/
oryxos serve --port 8080
./solutions/enterprise-controlled-rdops/scripts/accept.sh
```

## 默认安全

- Agent `tools` 仅 `retrieve_knowledge` + `read_file`（无 `shell` / `exec` / `write_file`）
- 权限样例全局 deny `shell` / `exec` / `write_file`；Agent 级 deny `ops.exec` / `ops.deploy`
- 危险执行须经 Flow `rdops-approve-exec` 的 `review`（human）节点
- `apply` **只绑定** `review.approved_params`，不回读 Agent 原始草案中的未批准参数
- 审批决策、工具调用、成本与结果写入审计/时间线，可回放对账
- `oryxos.flow.engine-enabled` / `approval.enabled` 仍默认 **false**

## 与 #478 / #479 边界

本包只覆盖**受控研发运维的危险写门禁、批准参数绑定与审计**。知识问答引用（#478）与工单审批补偿回放（#479）另包交付。
