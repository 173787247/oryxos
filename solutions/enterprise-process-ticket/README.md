# 业务流程与工单 Agent 标杆方案

Tracks: **#479**（Epic **#460**）。本目录是可复制的样例包：工单 Agent、审批策略、关键动作 HITL Flow、失败补偿与时间线回放、评测集与部署验收——**不改默认运行时行为**。

## 包内容

| 路径 | 用途 |
|------|------|
| [ACCEPTANCE.md](ACCEPTANCE.md) | #479 验收映射与独立验收清单 |
| [approval-policy.md](approval-policy.md) | 关键动作审批与幂等/补偿策略 |
| [permissions.sample.yml](permissions.sample.yml) | 工单场景工具权限样例 |
| [workspace/](workspace/) | 可直接拷入工作区的样例 |
| [scripts/accept.sh](scripts/accept.sh) | 新环境离线验收（无 LLM Key） |

## 快速落地

```bash
cp -a solutions/enterprise-process-ticket/workspace/. <your-workspace>/.oryxos/
oryxos serve --port 8080
./solutions/enterprise-process-ticket/scripts/accept.sh
```

## 默认安全

- 关键写操作（`ticket.apply_change`）**必须**经 Flow `human` 审批节点后才执行
- Agent `tools` 仅 `retrieve_knowledge` + `read_file`（无 shell / 无直接写工单）
- 权限样例全局 deny `shell` / `exec` / `write_file`；Agent 级 deny `ticket.apply_change`
- 失败时按 `compensate` 回滚，已成功节点幂等跳过，不重复副作用
- `FlowEngine.timeline(runId)` 可回放端到端节点链路
- `oryxos.flow.engine-enabled` / `compensation-enabled` 仍默认 **false**

## 与 #478 / #480 边界

本包只覆盖**工单/运营流程的审批、补偿与回放**。知识问答引用（#478）与受控研发运维危险写（#480）另包交付。
