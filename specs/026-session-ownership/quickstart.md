# Quickstart: 多副本正确性验收走查

**Feature**: 026-session-ownership | 契约见 [contracts/coordination.md](contracts/coordination.md)

前置：`mvn -q spotless:apply && mvn install`（确认新 fat jar——nested jar 校验含 V6__coordination.sql 与 TurnCoordinator，025 stale-jar 教训）。多副本档载体：双真进程 + 共享 PG（复用 025 走查的 PgServe + `config/application.yml` 三行配置 + `oryxos.cluster.enabled=true`、两进程不同 `instance-id` 与端口）。

## V1 单机档零回归（SC-008）

零配置（cluster.enabled 缺省 false）启动：全量既有功能走查抽查 + `mvn verify` 全绿；DB 侧断言零协调写（四张新表空、scheduled_tasks 新列全 NULL）。

## V2 同会话有序恰好一次（US1 / SC-001）

1. 双进程 A/B 就绪；经渠道入站路径（IM 契约测试手法模拟投递）对同一会话交替向 A/B 投 20 条消息（含把同一事件重复投给 A 和 B 各一次）
2. 断言：回答恰好 20 条、会话历史按序完整、日志零 `SessionUpdateConflictException`、`oryxos_duplicates_dropped_total` ≥ 重投数
3. IM 真机抽查（飞书凭证）：@bot 连发 3 问逐条恰好一答、顺序正确

## V3 独立会话并行（US1 / SC-007 前哨）

N=30 独立会话并发投给 A/B：全部完成、无跨会话等待（总耗时 ≈ 单会话耗时量级而非 30×）

## V4 调度恰好一次（US2 / SC-002）

双进程注册同一 Agent 的短周期任务（如每 10s），跑 10 个周期：task_executions 恰好 10 条、通知恰好 10 次；claimed_by 交替或集中均可（只要每 fire_time 恰一条）

## V5 故障接管与 fencing（US3 / SC-003/005/006）

1. A 处理某会话长轮次（慢工具/长 LLM）中 `kill -9` A → 租约到期后：该轮失败留痕（agent_executions）、用户不再无限等待
2. 用户下一条消息投 B：正常认领处理，历史含 A 崩溃前已落库上下文
3. fencing 演练（自动化 IT 覆盖，走查抽查）：暂停 A（SIGSTOP 模拟假死）超 TTL → B 抢过期开始处理 → `kill -CONT` A 苏醒 → A 的写回被 stillHeld 拒绝、日志现 fence_conflict、库中会话历史只有 B 的结果
4. `GET /api/v1/instances`：kill 前两实例存活；kill 后窗口过 A 显示已死
5. 企微属主（有企微配置时）：kill 持约进程 → 另一进程接管窗口内建连恢复；无企微环境时以 IT 的模拟独连型渠道验收

## V6 误配 fail-fast（SC-009）

三个组合各启动一次：cluster.enabled=true + ① `jdbc:sqlite:` ② `memory.backend=markdown` ③ `knowledge.store=memory` → 全部端口打开前拒启，报错指明修正方向

## V7 性能锚点（SC-007，数字落卷）

双进程 + PG，脚本化压测两种负载（mock provider 固定时延使吞吐可比）：
1. N=50 并发独立会话持续投递：双副本吞吐 ≥1.8× 单副本（同机基准先测单进程）
2. 单会话连发 20 条：端到端总时延与单机档同场景相当（±10%）
3. 从审计 duration 与租约日志估算协调开销占整轮 p99 比例 <1%

## 收尾

acceptance-report.md 落卷（V1~V7 + SC-001~009 对照 + 压测数字）；文档同步核对（CLAUDE.md 集群配置段、CliGuide、docker-compose 多副本注释更新——025 写的「多副本正确性随 026 交付」此刻兑现）。
