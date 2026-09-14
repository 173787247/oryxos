# Tasks: 文件面分布式（File Plane Distribution）

**Input**: Design documents from `/specs/027-file-plane/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R9）、data-model.md、contracts/、quickstart.md

**Tests**: 包含测试任务——spec 各 US 定义了 Independent Test，quickstart 定义了 IT 套件，宪法质量门禁要求全绿。

**Organization**: 按 User Story 分阶段，每阶段独立可测可交付。

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Flyway V8 迁移双 vendor 各一份：`oryxos-storage/src/main/resources/db/migration/sqlite/V8__file_plane.sql` 与 `postgresql/V8__file_plane.sql`——三表（workspace_versions / knowledge_build_claims / knowledge_generations，见 data-model.md）+ workspace_versions 预插 4 域行（version=0）；MigrationEvolutionIT/PostgresStorageE2ETest 的迁移计数断言同步 +1
- [X] T002 [P] `oryxos-core/src/main/java/io/oryxos/core/cluster/ClusterProperties.java` 新增 `workspace-poll-interval`（默认 1s，effective 方法风格沿既有），配套单测

---

## Phase 2: Foundational（阻塞所有 US）

- [X] T003 `oryxos-core/src/main/java/io/oryxos/core/cluster/CoordinationStore.java` 扩展 7 方法（bumpWorkspaceVersion / workspaceVersions / tryAcquireIndexBuild / renewIndexBuild / releaseIndexBuild / commitGeneration / committedGeneration，签名与语义见 contracts/coordination-store.md）
- [X] T004 oryxos-storage 新增实体与 Repository：`WorkspaceVersionEntity+Repository`（@Modifying 原子自增 + 全量查）、`KnowledgeBuildClaimEntity+Repository`（CAS 三式仿 TurnLeaseRepository）、`KnowledgeGenerationEntity+Repository`（条件提交 UPSERT），路径 `oryxos-storage/src/main/java/io/oryxos/storage/`
- [X] T005 `oryxos-storage/src/main/java/io/oryxos/storage/JpaCoordinationStore.java` 实现 7 新方法（DB 时间基准、rethrowUnlessConstraintViolation 纪律、commitGeneration 同事务五步：校验 claim → UPSERT 代次 → deleteGenerationsBelow → bump knowledge 域 → 释放 claim）
- [X] T006 `oryxos-storage/src/test/.../CoordinationStoreContractTest` 扩展契约用例（版本自增不丢/恒 4 域/claim 互斥抢过期 fencing/条件提交 rowcount/release 只删自己的），SQLite 与 PG 两个子类全绿
- [X] T007 [P] 新建 `oryxos-core/src/main/java/io/oryxos/core/io/AtomicFiles.java`（同目录临时文件写全 + ATOMIC_MOVE，不支持即抛不降级）+ 单测（含中途失败无半写断言）

**Checkpoint**: 协调面契约与原子写工具就绪，US1/US2/US3 可并行开工

---

## Phase 3: User Story 1 - 文件资产多副本一致可见与秒级生效（P1）🎯 MVP

**Goal**: A 副本的管理写操作，B 副本秒级可见并生效；单机档零回归。

**Independent Test**: FilePlaneVisibilityIT 双上下文 + 共享 PG + 共享临时工作区，US1 五个验收场景全绿；既有全量测试不红。

- [X] T008 [US1] 管理写路径接线版本号递增（先文件后 bump、cluster 关闭 NOOP）：`AgentLifecycleService`（create/update/delete/import → agents）、`AgentSkillBindingService`（bind/unbind/replaceBindings → agents）于 `oryxos-core/src/main/java/io/oryxos/core/agent/`、`.../skill/`
- [X] T009 [P] [US1] 同上接线其余域：`SkillService`（skills）、`PersonaService`（personas，oryxos-persona）、`KnowledgeApiController` upload/delete 与 `LocalKnowledgeBackend` deleteBase（knowledge，oryxos-web / oryxos-knowledge）
- [X] T010 [US1] `oryxos-core/.../agent/AgentLifecycleService.java` 新增 `reconcileAll()`：盘 ↔ ProfileRegistry 三向对账（新增 register / 变更 refresh / 消失 unregisterByDir），配套单测（含副本重启后按盘面重建场景）
- [X] T011 [US1] `oryxos-core/.../skill/SkillRegistry.java` 新增 `replaceAll()` 整体替换重载 + `SkillLoader` 重扫入口（复用既有软连接一致性检查），配套单测
- [X] T012 [US1] 新建 `oryxos-core/src/main/java/io/oryxos/core/cluster/WorkspaceVersionPoller.java`：每 tick 单查询全域、变化域回调（agents→reconcileAll、skills→replaceAll、personas→debug、knowledge→代次缓存失效）、读失败保留快照 WARN；单测用 fake CoordinationStore
- [X] T013 [US1] `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 档位装配切换：cluster 档不装 WorkspaceWatcher/KnowledgeWatcher、装 WorkspaceVersionPoller；单机档零变化
- [X] T014 [US1] 新建 `oryxos-boot/src/test/java/io/oryxos/boot/FilePlaneVisibilityIT.java`（双上下文 + 共享 PG + 共享临时工作区）：US1 验收场景 1~5（A 建 B 见、A 改 B 用新配置、A 删 B 失、单机零变化、重启对账），另断言单机档零版本号写入

**Checkpoint**: US1 独立可交付——双副本管理台不再「抽奖」

---

## Phase 4: User Story 2 - 知识索引流水线多副本恰好执行一次（P2）

**Goal**: 索引构建恰好一次 + 超时接管；检索恒读已提交代次（顺带修单机竞态隐患）。

**Independent Test**: KnowledgeExactlyOnceIT 双副本同触发恰一执行、kill 接管、检索一致；单机档知识全量测试零回归。

- [X] T015 [US2] `oryxos-knowledge/src/main/java/io/oryxos/knowledge/index/KnowledgeIndexService.java` 活跃代次改造：activeGeneration 改读 `CoordinationStore.committedGeneration`（每副本缓存 + 总线失效回调），替换 max(generation) 推断，空态兼容首建；importDocument/rebuild 的代次提交改走 commitGeneration；配套单测
- [X] T016 [US2] 同文件 rebuild 与 importDocument 接认领：集群档 rebuild 先 tryAcquireIndexBuild（失败返回「构建进行中」明确提示）、每文档一续 renewIndexBuild（失败立即中止丢弃本代）、完成条件提交、异常路径 releaseIndexBuild + 丢弃新代；**importDocument 的异步索引段同走该认领（短持有，claim 被 rebuild 持有期间排队等待）**——收口跨副本 import↔rebuild 竞态（analyze U1）；单机档 NOOP 直通；配套单测
- [X] T017 [US2] 新建 `oryxos-boot/src/test/java/io/oryxos/boot/KnowledgeExactlyOnceIT.java`：双副本同时 rebuild 恰一执行、kill 持有者 TTL 后接管完成、构建全程检索恒读已提交代次、**A rebuild 中 B import 不丢文档（US2 场景 5）**、单机档零协调写

**Checkpoint**: US2 独立可交付——embedding 成本不再翻倍、代次不互覆

---

## Phase 5: User Story 3 - 共享卷部署的行为边界清晰可依（P3）

**Goal**: 原子写收口 + 手动刷新逃生舱 + 支持矩阵文档。

**Independent Test**: 原子写单测全绿；refresh 端点双档行为正确；ClusterStartupCheckTest 既有 5 用例回归。

- [X] T018 [P] [US3] `oryxos-core/.../skill/SkillStore.java` write/writeAll 改走 AtomicFiles（rollbackCreate 语义保持），配套单测
- [X] T019 [P] [US3] `oryxos-persona/src/main/java/io/oryxos/persona/PersonaStore.java` write 改走 AtomicFiles，配套单测
- [X] T020 [P] [US3] `oryxos-web/.../controller/KnowledgeApiController.java` upload 落盘与 `oryxos-core/.../agent/AgentStore.java` restore 改走 AtomicFiles，配套单测
- [X] T021 [US3] `oryxos-web/.../controller/WorkspaceApiController.java` 新增 `POST /api/v1/workspace/refresh`（契约见 contracts/workspace-api.md：cluster 档 bump 全域 / 单机档本地全量重载），MockMvc 测试双档行为
- [X] T022 [P] [US3] 新建 `docs/SharedVolumeGuide.md`：依赖声明（close-to-open + 同卷 rename 原子）、不依赖声明（文件锁/inotify）、NFS 挂载建议、K8s RWX PVC 建议、不支持配置清单

**Checkpoint**: US3 独立可交付

---

## Phase 6: Polish & Cross-Cutting

- [X] T023 指标：`oryxos-core/.../metrics/MetricsRecorder.java` 新增 `recordWorkspaceReloaded(String domain)`；`oryxos-cli/.../MicrometerMetricsRecorder.java` 实现（`oryxos_workspace_reloads_total{domain}`）；索引认领路径挂 recordLeaseAcquired/Reclaimed/FenceConflict（kind="index"）；补 026 遗留的 `recordLeaseAcquired("schedule")` 埋点（AgentScheduler claimFireTime 赢者路径）
- [X] T024 [P] 文档同步：CLAUDE.md（027 配置项与行为一句话 + 模块表无变更确认）、`config/application.yml.example`（workspace-poll-interval 注释样例）、docs/CliGuide.md 集群段落；顺手修 `sqlite/V7__coordination.sql` 首行注释笔误（V6→V7）
- [X] T025 全量门禁 + 验收落卷：`mvn clean install`（含 IT）BUILD SUCCESS，其中 **ClusterStartupCheckTest 既有 5 用例回归绿为 SC-005 显式验收项**；按 quickstart V2/V3 双真进程走查与单机走查；如实撰写 `specs/027-file-plane/acceptance-report.md`（SC-001~007 对照；SC-006 轮询负载以机理证据落卷——每副本每秒 1 次 4 行单查询；NFS 抽查视环境如实记录）

---

## Dependencies

```
Phase 1 (T001,T002) → Phase 2 (T003→T004→T005→T006; T007 独立)
Phase 2 完成后：US1 (T008~T014)、US2 (T015~T017)、US3 (T018~T022) 三线可并行
  US1 内：T008/T009 并行 → T010/T011 并行 → T012 → T013 → T014
  US2 内：T015 → T016 → T017（T015 依赖 T005 的 committedGeneration）
  US3 内：T018/T019/T020/T022 并行（依赖 T007）；T021 依赖 T008/T009 的 bump 接线
         及 US1 的 T010/T011（单机档刷新路径调 reconcileAll/replaceAll）——US3 完整交付须在 US1 之后
Phase 6：T023 依赖三线主体；T024 可随时并行；T025 收尾必须最后
```

## Parallel Execution Examples

- Phase 2 起步：T007（AtomicFiles）与 T003~T006（协调面链）双线并行
- Phase 2 完成后三个 Story 可三线并行（不同模块文件面基本不相交；OryxOsRuntime 装配集中在 T013 单点避免冲突）
- US3 的 T018/T019/T020/T022 四任务完全并行（四个不同文件）

## Implementation Strategy

- **MVP = Phase 1 + 2 + US1（T001~T014）**：双副本管理面一致可见即为本刀最小可演示价值
- 增量交付：US1 → US2 → US3 → Polish，每 Checkpoint 一次提交 + 全量绿
- 单机档零回归是每个 Checkpoint 的固定断言（既有测试全量跑）
