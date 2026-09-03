# Contract: 多副本协调语义

**Feature**: 026-session-ownership | **Date**: 2026-09-03 | 原则 VIII：实例无状态、状态外置

## 1. 互斥承诺（turn）

1. 同一会话任一时刻至多一个副本执行其消息处理；互斥粒度=「正在执行的一轮」，执行完即释放——无会话到副本的永久归属，无粘性路由。
2. 同会话后到消息等待前序完成后按序处理（跨副本排队与单机排队语义等价）；等待有上限（wait-timeout），超限按既有超时口径反馈用户。
3. 不同会话零竞争全并行；群聊问答（无状态独立会话）不参与互斥。
4. 续租失败即失去持有：执行方立即中止且**绝不写回**（写回前硬校验兜底）；两个副本同时认为自己持有同一轮的窗口不存在于写回路径。
5. 单机档（cluster.enabled=false）：协调全 NOOP，行为与本特性交付前完全一致。

## 2. 恰好一次承诺（schedule / dedup）

- 定时任务同一次到点恰好一个副本执行（到点时刻 CAS）；执行中崩溃按失败记录，**不补发**；手动「立即执行」不参与到点互斥（用户显式触发语义）。
- 入站事件判重跨副本生效（回执落共享库）：同一事件标识恰好处理一次，重推/重发丢弃且计数（指标）；单机档保持进程内判重行为。

## 3. 接管承诺（failover）

- 副本崩溃后其持有的轮次在租约到期后可被抢占：悬空轮补失败留痕、**不重放**；接管取得的是下一轮执行权。
- 回收只针对已过期持有（条件抢占），任何路径不得全量清除他人持有（含启动期）。
- 独连型渠道（企微）连接由持约副本唯一建立；属主死后接管窗口内另一副本建连恢复收发；多副本永不互踢。
- 快速重启的新进程（同 instance-id 新 epoch）不继承旧代持有。

## 4. 可见性承诺

- `GET /api/v1/instances`：全部实例（id/epoch/启动时刻/最近心跳/存活判定）+ 现役轮次持有（谁在处理哪个会话）。
- 指标：`oryxos_leases_acquired_total` / `oryxos_leases_reclaimed_total` / `oryxos_fence_conflicts_total`（标签 kind=turn/schedule/channel）、`oryxos_duplicates_dropped_total`（标签 channel）——命名自本版本固化只增不改。
- 认领/回收/续租失败各产出结构化日志（脱敏，CRLF sanitize）。

## 5. 兼容性承诺

- ReActLoop / StreamListener / SessionManager / REST / SSE / 审计口径零改动；`MessageDeduplicator.markIfFirst` 签名不变（类型抽接口）。
- 新表走 025 迁移体系（V6 双 vendor），SQLite/PG 同语义；单机档零新增必填配置、零协调写。
- 误配组合（cluster.enabled + SQLite / markdown 记忆 / memory 知识库）启动即拒绝并指明修正方向，端口打开前失败。
- 时间判定以数据库时间为准；副本本地时钟漂移（NTP 秒级）不影响正确性。
