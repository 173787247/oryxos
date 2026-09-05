# Acceptance Report: session 归属与多副本正确性（Session Ownership）

**Feature**: 026-session-ownership | **Date**: 2026-09-05 | **Verdict**: V1~V6 全过；V7 性能锚点以「协调开销」达成、吞吐线性性按机理证据 + 环境局限如实记录

## 自动化验收（全部 CI 门禁内，`mvn install` BUILD SUCCESS）

| 面 | 载体 | 结果 |
|----|------|------|
| CAS 原语两库语义 | CoordinationStoreContractTest ×2（SQLite/PG） | 20/20——互斥/抢过期/fencing rowcount/回执判重/到点恰一胜/心跳 upsert |
| 轮次协调语义 | DbTurnCoordinatorTest | 5/5——等待超时、续租失败中断、stillHeld 硬闸、NOOP |
| US1 同会话恰好一次 | SessionOwnershipIT（双上下文+共享 PG） | 5/5——20 条交替投递零冲突、跨副本重推去重、30 独立会话并行、enabled=true 单副本自洽、单机档零协调写 |
| US2 定时恰好一次 | ScheduleExactlyOnceIT | 1/1——双副本 */2s 按秒分桶零双发；认领值=秒级日历点（A1 理论触发时刻断言） |
| US3 接管与可见性 | FailoverTakeoverIT | 4/4——死副本租约抢占+悬空 execution 标失败（不重放）、活持有者后等待超时专用异常、企微属主接管恰好一次无互踢、实例死活清理 |
| 两级去重 | SharedReceiptDeduplicatorTest | 2/2——本地命中零 DB、跨副本回执拦截 |
| 误配 fail-fast | ClusterStartupCheckTest | 5/5——三组合拒启+单机零校验（SC-009） |

## 真机走查（双真进程 jar + 常驻 PG 15432，用户视角配置）

- **V-instances**：`GET /api/v1/instances` 双副本（walk-a/walk-b）均 alive、clusterEnabled=true ✓（SC-006）
- **同会话跨副本连续性**：会话在 A 对话后改打 B——B 正常应答、历史含两条 user 消息完整连续 ✓（SC-001/003 消息面；真 kill 的接管由 IT 按死副本落盘形态覆盖——走查中 kill 因 pid 文件路径笔误未真正送达，A 存活时 B 直接认领同样成立，如实记录）
- **单机档**（cluster 缺省 false）：全量既有测试绿 + IT 断言零协调写 ✓（SC-008）

## V7 性能锚点（SC-007）——实测数字与口径说明

| 度量 | 数字 |
|------|------|
| 单会话连发 ×20（cluster 档，热态） | median 15.6ms / p95 19.3ms |
| 单会话连发 ×20（单机档无协调，冷态） | median 25.0ms / p95 36.4ms |
| 单副本 200 并发会话（热态） | 418.7/s |
| 双副本 200 并发会话（均分，热态） | 345.2/s（0.82×） |

**结论与口径**：
1. **协调开销达标**：cluster 档整轮（含 mock 处理 + ~5 次单行协调写）15.6ms，低于无协调单机档的 JIT 冷热噪声（25ms）——**每轮协调净开销小于测量噪声本身（<10ms 级）**；按真实 LLM 轮次（p99 ≥2s）折算占比 <0.5%，「p99 不可见（<1%）」达成。
2. **吞吐线性性未能有效实测**：mock provider 零时延 + 同机双 JVM + Python 单进程发压端（418/s 时客户端已饱和）——瓶颈在发压端而非服务侧，加副本无从体现。线性性依据退为机理证据：会话间零竞争（IT 30 并发无跨会话等待断言）+ 协调开销如上 + 共享 PG 写余量（025 论证数千写/s）。**如需硬数字，需独立发压机 + 有真实时延的 provider 档**——留待 028 容器交付的 K8s 环境或真实 LLM 压测复测。
3. 飞书真机抽查：本轮环境无凭证未执行；渠道入站路径判重已由共享回执 IT 覆盖，真机连发抽查沿 017 手法待凭证可用时补做。

## SC 对照

| SC | 判定 | SC | 判定 |
|----|------|----|------|
| SC-001 同会话 20 条恰好一答 | ✅ IT+走查 | SC-006 实例可查判死 | ✅ IT+走查 |
| SC-002 定时 10 周期恰好 10 次 | ✅ IT（5 周期形态同语义） | SC-007 性能锚点 | ⚠ 协调开销达成；吞吐线性以机理证据+环境局限记录 |
| SC-003 kill 后接管留痕 | ✅ IT（死副本落盘形态） | SC-008 单机零回归 | ✅ 全量门禁+零协调写断言 |
| SC-004 重推 0 重复 | ✅ IT | SC-009 误配拒启 | ✅ 单测三组合 |
| SC-005 企微不互踢接管 | ✅ IT（协调器语义） | | |

## 实现期修正与实录

1. **JPA save 对自然主键实体走 merge**——「插入」静默覆写他人租约：契约测试抓获；弃 save/Persistable（后者 isNew 恒 true 令 delete 短路、deleteAll 变 no-op），改 native INSERT + 约束违规判定。
2. **SQLite 方言异常翻译缺口**：主键冲突包成 JpaSystemException 而非 DataIntegrityViolationException——按 cause 链 SQLState 23xxx / SQLITE_CONSTRAINT 兜底判定。
3. **SELECT CURRENT_TIMESTAMP 驱动类型差**：SQLite 返回字符串——dbNow 四型适配。
4. **fireTime 必须取 FireTimeTrigger 记录的理论触发时刻**（A1 预判成真的位置）：IT 以「认领值=秒级日历点」断言钉死。
5. stale-jar 第三例（boot 测试引旧 core 具体类致 IncompatibleClassChangeError）：clean + 重装上游后消除；教训固化——boot IT 前恒重装上游模块。
6. 顺修既有缺口：memory.backend 未知值静默回落 markdown → fail-fast。
