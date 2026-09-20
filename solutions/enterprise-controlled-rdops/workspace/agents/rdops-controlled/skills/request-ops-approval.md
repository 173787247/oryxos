# Skill: 请求运维执行审批

触发条件：分诊结果 `needs_approval: yes`，或拟执行任何 shell / 部署 / 写操作。

输出（纯文本）：

```text
APPROVAL_REQUIRED
change_id: <id>
proposed_command: <草案命令>
risk: <low|medium|high>
reason: dangerous_write | policy_gate | high_risk
```

然后停止。由 Flow `rdops-approve-exec` 将草案交给 `review` human 节点。

禁止：

- 调用 `ops.exec` / `ops.deploy` / `shell` / `exec` / `write_file`
- 在未获 `decision == approved` 前声称「已执行」
- 假设审批人会原样批准 `proposed_command`（最终以 `approved_params` 为准）
