# Implementation Plan: 文件面分布式（File Plane Distribution）

**Branch**: `027-file-plane` | **Date**: 2026-09-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/027-file-plane/spec.md`

## Summary

让文件形态的资产（Agent 目录、Skill 库、人格、知识源文件）在多副本间可见、可感知、不打架：文件本体走共享卷（只依赖读写可见 + rename 原子），变更感知走「DB 版本号总线 + 秒级轮询」（新表 `workspace_versions`，集群档替换 WatchService watcher，单机档零回归）；知识索引构建经 `knowledge_build_claims` CAS 认领恰好一次 + 超时接管，已提交代次显式落 `knowledge_generations` 表（同时修掉「max(generation) 推断」在集群下必错、单机下存在竞态的隐患）；全部工作区写路径收口为「临时文件 + 原子改名」；提供手动刷新入口作运维直接改盘的逃生舱。全部协调复用 026 CoordinationStore 形态，零新机制。

## Technical Context

**Language/Version**: Java 21（virtual thread）

**Primary Dependencies**: Spring Boot 3.x、Spring Data JPA、Flyway、Micrometer（均既有，零新依赖）

**Storage**: SQLite（单机默认）/ PostgreSQL（集群档必须，026 已 fail-fast）；新增 Flyway V8（双 vendor 纯 SQL：`workspace_versions` + `knowledge_build_claims` + `knowledge_generations`）

**Testing**: JUnit 5 + Spring Boot Test；契约测试沿 `CoordinationStoreContractTest` 双库形态扩展；IT 沿 026 双上下文 + 共享 PG 手法（`SessionOwnershipIT` 样板）

**Target Platform**: Linux server（WSL2 开发、K8s/VM 部署）；共享卷 NFS / K8s RWX PVC

**Project Type**: Maven 多模块单体（既有模块内改动，无新模块）

**Performance Goals**: 变更传播 ≤3s（SC-001）；集群档空闲期文件面新增负载 = 每副本每秒 1 次 4 行单查询（SC-006 <1%）

**Constraints**: 单机档零配置零行为变化（不轮询、不写版本号、watcher 原样）；同步阻塞 + 虚拟线程（无异步模型）；共享卷只依赖 close-to-open 一致性 + 同卷 rename 原子性

**Scale/Scope**: 双副本为验收基线（与 025/026 同口径）；域固定 4 个；涉及 6 个既有模块（core/knowledge/storage/persona/web/cli）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 说明 |
|------|------|------|
| I 自实现 ReAct | ✅ 不涉 | 不触碰循环 |
| II Spring AI 两件事 | ✅ 不涉 | 无 LLM 面改动 |
| III Provider 显式映射 | ✅ 不涉 | — |
| IV 目录=Agent；软连接绑定 | ✅ 合规 | 重载全部复用 AgentLoader/SkillLoader 既有一致性检查（dangling/escaped 等照跑）；不新增绑定表达 |
| V 审计 Day One | ✅ 合规 | 无新审计面；既有 tool_invocations/llm_calls 不变 |
| VI 安全地基 | ✅ 合规 | AtomicFiles 仅服务端管理写路径（非 Tool 面，不涉沙箱豁免）；软连接真实路径校验既有逻辑不动 |
| VII 同步 + 虚拟线程 | ✅ 合规 | poller 为 TaskScheduler 定时同步任务；索引异步段沿既有 executor 形态不变 |
| VIII 状态外置 + Flyway | ✅ 合规 | 版本号/认领/代次全部落库；V8 双 vendor 纯 SQL，不用 ddl-auto |
| 模块演进条款 | ✅ 合规 | 无新模块；跨模块契约（CoordinationStore 扩展）仍在 oryxos-core，storage 实现（依赖倒置） |

**Post-Phase-1 re-check**: ✅ 通过（设计未引入新违背；Complexity Tracking 空）。

## Project Structure

### Documentation (this feature)

```text
specs/027-file-plane/
├── plan.md              # 本文件
├── research.md          # Phase 0（R1~R9 全部裁决，无遗留 NEEDS CLARIFICATION）
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/           # Phase 1（coordination-store.md + workspace-api.md）
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令产物）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── cluster/
│   ├── CoordinationStore.java        # [改] 新增 workspace version + index build claim 方法
│   ├── ClusterProperties.java        # [改] 新增 workspace-poll-interval（默认 1s）
│   └── WorkspaceVersionPoller.java   # [新] 集群档秒级轮询 → 分域触发重载（含域→回调注册）
├── agent/
│   ├── AgentLifecycleService.java    # [改] 新增 reconcileAll()（盘面 ↔ ProfileRegistry 全量对账）
│   └── AgentStore.java               # [改] restore() 改走 AtomicFiles
├── skill/
│   ├── SkillStore.java               # [改] write/writeAll 改走 AtomicFiles
│   └── SkillRegistry.java            # [改] 新增 replaceAll()（重载整体替换，复用一致性检查）
└── io/
    └── AtomicFiles.java              # [新] 同目录临时文件 + ATOMIC_MOVE，不支持即抛（不降级）

oryxos-knowledge/src/main/java/io/oryxos/knowledge/
├── index/KnowledgeIndexService.java  # [改] activeGeneration 改读已提交代次表（缓存+失效）；
│                                     #      rebuild 接 CAS 认领/按批续租/条件提交
└── watch/KnowledgeWatcher.java       # [不改] 集群档由装配层不装配

oryxos-persona/src/main/java/io/oryxos/persona/
└── PersonaStore.java                 # [改] write 改走 AtomicFiles

oryxos-storage/src/main/
├── java/io/oryxos/storage/
│   ├── JpaCoordinationStore.java     # [改] 实现新增方法（版本号自增读写、index claim CAS、代次条件提交）
│   └── （新增 3 个 Repository + 2 个实体：WorkspaceVersion、KnowledgeBuildClaim；
│         knowledge_generations 走既有知识实体旁路或新实体，见 data-model）
└── resources/db/migration/
    ├── sqlite/V8__file_plane.sql     # [新] 三表 + workspace_versions 预插 4 域行
    └── postgresql/V8__file_plane.sql # [新] 同号同内容（方言差异极小）

oryxos-web/src/main/java/io/oryxos/web/controller/
├── WorkspaceApiController.java       # [改] 新增 POST /api/v1/workspace/refresh
└── KnowledgeApiController.java       # [改] upload 落盘改走 AtomicFiles

oryxos-cli/src/main/java/io/oryxos/cli/
├── OryxOsRuntime.java                # [改] 集群档条件装配：不装 WorkspaceWatcher/KnowledgeWatcher，
│                                     #      装 WorkspaceVersionPoller；管理写路径 bean 注入版本号递增
└── MicrometerMetricsRecorder.java    # [改] recordWorkspaceReloaded 实现 +
                                      #      顺手补 recordLeaseAcquired("schedule") 埋点

docs/
└── SharedVolumeGuide.md              # [新] 共享卷支持矩阵（依赖/不依赖/NFS 与 PVC 挂载建议）

测试（关键新增）：
oryxos-storage/src/test  → CoordinationStoreContractTest 扩展（版本号/认领/条件提交，双库）
oryxos-core/src/test     → WorkspaceVersionPoller 单测、AtomicFiles 单测、reconcileAll 单测
oryxos-boot/src/test     → FilePlaneVisibilityIT（双上下文+共享 PG+共享临时工作区：US1 秒级可见）
                           KnowledgeExactlyOnceIT（US2 恰好一次 + kill 接管 + 检索一致）
                           单机档回归（watcher 行为 + 零协调写断言，沿 026 手法）
```

**Structure Decision**: 无新模块。跨模块契约（CoordinationStore 扩展、AtomicFiles、poller 回调注册）全部落 `oryxos-core`，`oryxos-storage` 做 JPA 实现、`oryxos-cli` 做装配（与 026 完全同构的依赖倒置分层）。写路径分散在 core/persona/web 三处的原子化收口不动模块边界。

## 实现要点（按 US 分组）

### US1 文件资产秒级一致（P1）
1. V8 迁移 + `workspace_versions` 实体/Repository + `CoordinationStore.bumpWorkspaceVersion / workspaceVersions()`（一次读全域）。
2. 管理写路径接线：AgentLifecycleService（create/update/delete/import/绑定变更后 bump `agents`）、SkillService（bump `skills`）、PersonaService（bump `personas`）、Knowledge upload/delete（bump `knowledge`）。**先文件后版本号**；单机档 NOOP（cluster 关闭零写入）。
3. `WorkspaceVersionPoller`：初始 last-seen 取启动时读值；每 tick 全域单查询，变化域回调——agents→`reconcileAll()`、skills→`SkillRegistry.replaceAll(SkillLoader.loadAll())`、personas→debug 日志、knowledge→清代次缓存（+`reconcile` 由刷新入口触发时执行）。读失败保留快照并 WARN（spec Edge Case）。
4. 装配切换：`clusterProps.isEnabled()` 分支装配 poller 或两个 watcher。
5. `reconcileAll()`：盘 ↔ 注册表三向对账（新增/变更/消失），全部复用既有 register/refresh/unregisterByDir。

### US2 知识索引恰好一次（P2）
6. `knowledge_build_claims` + `knowledge_generations` 实体/Repository；`CoordinationStore` 新增 `tryAcquireIndexBuild / renewIndexBuild / releaseIndexBuild / commitGeneration(kbName, generation, owner)`（条件提交：claim 仍属 owner 才写代次，rowcount 语义）。
7. `KnowledgeIndexService.rebuild`：集群档先认领（失败=别人在建，返回明确提示）；按文档批次续租，续租失败立即中止丢弃本代；完成走条件提交（提交内含 deleteGenerationsBelow + bump knowledge 域）。`activeGeneration` 改读 `knowledge_generations`（每副本缓存 + 总线失效），首建库（无代次行）兼容空态。
8. 单机档：认领/续租 NOOP 直通，代次表照用——检索口径不变，竞态隐患顺带修复。

### US3 行为边界（P3）
9. `AtomicFiles` + 四处非原子写收口（SkillStore、PersonaStore、Knowledge upload、AgentStore.restore）。
10. `POST /api/v1/workspace/refresh`（集群档 bump 全域；单机档本地全量重载）。
11. `docs/SharedVolumeGuide.md` 支持矩阵；`ClusterStartupCheck` 零新增（回归既有 5 用例）。
12. 指标：`recordWorkspaceReloaded(domain)` + index kind 复用租约三指标 + 补 schedule 认领埋点。

## Complexity Tracking

> 无宪法违背，无需填写。
