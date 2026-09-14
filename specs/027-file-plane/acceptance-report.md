# Acceptance Report: 文件面分布式（File Plane Distribution）

**Feature**: 027-file-plane | **Date**: 2026-09-14 | **Verdict**: SC-001~006 全过；SC-007 文档达成 + 同机共享目录真机走查通过，NFS 真卷抽查留待有 NFS 环境时按 SharedVolumeGuide 补做（如实记录）

## 自动化验收（`mvn clean install` BUILD SUCCESS + 显式 IT 套件）

> 口径说明（如实）：本仓 `*IT` 命名类不在 surefire 默认包含范围，`mvn verify/install` 门禁只跑 `*Test`。
> 本刀与 026 的 IT 均以显式 `-Dtest=` 执行并全绿；门禁本身（单测 + Spotless/Checkstyle/PMD-P3C/SpotBugs 全量）BUILD SUCCESS。

| 面 | 载体 | 结果 |
|----|------|------|
| 版本号/认领/代次两库契约 | CoordinationStoreContractTest ×2（SQLite/PG） | 新增 6 组全绿——版本自增不丢/恒 4 域/claim 互斥抢过期 fencing/条件提交 rowcount/提交即 bump/release 只删自己的 |
| 轮询语义 | WorkspaceVersionPollerTest | 4/4——变化才重载、读失败保快照自愈、单域失败重试不阻断、未知域拒注册 |
| 原子写 | AtomicFilesTest | 3/3——写全落位、覆盖无残留、失败零半写零临时残留 |
| US1 秒级可见 | FilePlaneVisibilityIT（双上下文 + 共享 PG + 共享临时工作区） | 6/6——A 建/改/删 B 3s 内一致、集群档 poller on/watcher off 且单机档反向、单机 NOOP 通知、重启按盘面重建 |
| US2 恰好一次 | KnowledgeExactlyOnceIT | 4/4——双副本同时 rebuild 恰一执行代次一致、死持有者过期认领被接管、**rebuild 持有期间 import 排队不丢（U1）**、单机档零认领写 |
| refresh 端点双档 | WorkspaceApiControllerTest | 2/2——集群档 bump 全 4 域零本地重载、单机档本地重载零版本号写 |
| 026 回归 | SessionOwnershipIT 5/5 + ScheduleExactlyOnceIT 1/1 + FailoverTakeoverIT 4/4 | 全绿——装配面改动（watcher 条件化/协调面扩展）零破坏 |
| 迁移演进 | MigrationEvolutionIT 3/3 + ConcurrentMigrationIT 1/1 + PostgresStorageE2ETest（计数 4） | 全绿；MigrationEvolutionIT 尾部收敛断言更新为 V8（幂等 SQL） |
| 误配拒启回归（SC-005） | ClusterStartupCheckTest | 5/5 既有用例不变绿（本刀零新增组合，按 R7 裁决走文档） |
| 单机档零回归 | 全量单测（门禁内） | 645+ 全绿，SkillStore/PersonaStore/AgentStore 原子化改造零行为断言变化 |

## 真机走查（双真进程 jar `serve` + 常驻 PG 15432 干净库 + 同机共享目录模拟共享卷）

- **V-instances**：`GET /api/v1/instances` walk-a/walk-b 双 alive、clusterEnabled=true ✓
- **US1 A 建 B 见**：经 A `POST /api/v1/agents` 建 walk-cross → **3s 内** B 列表可见；经 A DELETE → 3s 内 B 消失 ✓（SC-001）
- **FR-011 逃生舱**：SSH 直接改共享目录上 AGENT.md → `POST /api/v1/workspace/refresh`（mode=cluster，bump 全 4 域）→ 3s 内另一副本列表反映改动 ✓
- **US2 知识跨副本**：A 建库 + 上传 → B 查询同库状态就绪、documentCount/chunkCount 一致 ✓；**双副本同时 `POST reindex`：一个 200 执行、另一个 409「重建进行中」——恰好一次真机复现** ✓（SC-002）
- **可观测**：`/actuator/prometheus` 见 `oryxos_leases_acquired_total{kind="index"}=2`、`oryxos_workspace_reloads_total{domain="agents"}=3` ✓
- **单机档（V3）**：默认配置（cluster 缺省）`serve` 启动，直接改盘 AGENT.md → **watcher 3s 内生效**、日志零轮询条目 ✓（SC-006 单机面/FR-010）
- 走查环境注记：常驻 PG 的 `postgres` 库残留旧走查 V6 校验和（历史状态非产品问题），走查改用新建 `oryxos_walk027` 干净库，V1~V8 全量迁移一次通过。

## SC 对照

| SC | 判定 | 说明 |
|----|------|------|
| SC-001 3s 可见 | ✅ | IT 断言 + 真机走查（建/删/改盘刷新均 ≤3s） |
| SC-002 索引恰好一次 | ✅ | IT + 真机 200/409 并发复现 |
| SC-003 kill 接管 | ✅ | IT（死持有者过期认领形态，026 FailoverTakeoverIT 同手法） |
| SC-004 无半写 | ✅ | AtomicFilesTest + 四处写路径收口 |
| SC-005 误配拒启回归 | ✅ | ClusterStartupCheckTest 5/5（零新增组合按 R7 裁决） |
| SC-006 单机零回归 + 轮询负载 | ✅ | 全量单测绿 + 真机单机走查；负载机理证据：每副本每 tick 一次 4 行单查询（workspace_versions 恒 4 行），1s 间隔 = 每秒 1 读，对共享 PG（数千写/s 余量，025 论证）占比 <0.1% |
| SC-007 支持矩阵 + 真卷走查 | ⚠️ 部分 | `docs/SharedVolumeGuide.md` 已交付；同机共享目录走查全过（满足读写可见 + rename 原子的最强形态）；**NFS 真卷抽查本轮环境无 NFS 未执行**，留待 028 K8s（RWX PVC）环境按 quickstart V4 补做 |

## 遗留与如实记录

1. **LegacyTakeoverIT 存量失败（非本刀引入）**：在 027 起点（45dc652，026+main 合并点）基线复测同样失败——`SecretMigration` 读取 legacy 种子库 notify_channels 的 ISO-8601 'Z' 格式时间戳解析失败，系 main 侧演进带入且 `*IT` 不进默认门禁未被发现。已留证据（基线复测 Tests run: 1, Errors: 1），建议单独开 issue 修复（属 022 凭证迁移面，非 027 范围）。
2. **MigrationEvolutionIT 同为基线失败**（删 V6 行在 V7 存在时即被 Flyway out-of-order 拒），本刀已按「尾部=V8 幂等 SQL」修正断言，3/3 恢复绿。
3. `*IT` 不进 surefire 默认门禁属仓库既有口径（surefire 无 `*IT` include、无 failsafe）；建议后续统一 IT 纳管方式（改名 `*Test` 或引入 failsafe），本刀不动门禁配置。
4. commitGeneration 的事务边界按实现裁决收窄：旧代片段清理（deleteGenerationsBelow）移出协调面事务、由调用方提交成功后执行（GC 语义，残留由下次重建清理）——contracts/coordination-store.md 已同步。
