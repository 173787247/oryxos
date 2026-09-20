# 配置回滚手册

1. 记录变更前快照 ID。
2. `apply` 失败或人工取消后，调用 `ticket.rollback` 一次。
3. 已成功节点按幂等键跳过，禁止重复 apply。
