# 只读巡检手册

1. 优先建议只读命令（如 `kubectl get pods -n <ns>`）。
2. 拟执行命令写入草案 `proposed_command`。
3. **必须**经审批 Flow；执行参数以人工 `approved_params` 为准。
4. 禁止在未批准时调用 `ops.exec`。
