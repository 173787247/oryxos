# Research: 文件面分布式（027-file-plane）

**Date**: 2026-09-14 | **Input**: spec.md + 代码面调查（WorkspaceWatcher / AgentLifecycleService / KnowledgeIndexService / CoordinationStore / ClusterStartupCheck / Flyway 现状）

## R1. 变更感知总线的载体与轮询形态

- **Decision**: 新增 `workspace_versions` 表（`domain` PK + `version` 单调递增 + `updated_by/updated_at`），域取 `agents / skills / personas / knowledge` 四值。集群档新增 `WorkspaceVersionPoller`（复用 ThreadPoolTaskScheduler，默认 1s，配置 `oryxos.cluster.workspace-poll-interval`），**一次单查询读全部域**（表恒 4 行），与本地 last-seen 比对，变化域触发对应重载。管理写路径在文件落盘**之后**经 `CoordinationStore.bumpWorkspaceVersion(domain, owner)` 递增（DB 侧 `version = version + 1` 原子自增，无 CAS 竞争问题）。
- **Rationale**: 「DB 作通知总线、文件作内容载体」是规划裁决；先文件后版本号保证读到新版本号必能读到新内容（NFS close-to-open）。每秒 1 次 4 行读满足 SC-006（<1% 负载占比）。
- **Alternatives considered**: LISTEN/NOTIFY（PG 专有，SQLite 无对应物且引入连接常驻复杂度，拒）；每域一行逐条查（4 次往返不如一次全查）；文件 mtime 扫描（NFS 时间戳精度与缓存不可靠，拒）。

## R2. 各域重载的落点（现状差异大，逐域裁决）

- **Decision**:
  - **agents**：新增 `AgentLifecycleService.reconcileAll()`——扫盘 `.oryxos/agents/` 与 ProfileRegistry 全量对账：盘上有注册表无 → `register`；两边都有 → `refresh`（复用既有目录级路径，幂等）；注册表有盘上无 → `unregisterByDir`。ProfileRegistry 保持 ConcurrentHashMap 原地更新（`register` 即 put 覆盖），Profile 对象本身不可变即满足 spec「快照式替换、不热改在途」——在途轮次持有的是旧 Profile 引用。
  - **skills**：`SkillLoader.loadAll()` 现只在启动跑一次。新增 `SkillRegistry.replaceAll(loadAll())` 式重载入口（构建新集合后整体替换，含既有软连接一致性检查）。
  - **personas**：`PersonaStore` 现状按需读盘、无内存缓存——重载为 **no-op**，共享卷可见性已足够。写路径仍统一递增版本号（保持总线纪律 + 未来引入缓存不改协议），poller 收到变化仅记 debug 日志。
  - **knowledge**：重载 = 清 `KnowledgeIndexService.activeGenerations` 缓存（改为读 R4 的已提交代次表）。**调查发现的关键坑**：当前 activeGeneration 靠「documents 表 max(generation) 惰性推断」——副本 B 缓存旧代次，A 重建切代并 `deleteGenerationsBelow` 后，B 继续用已删除代次检索 → 集群档必错。总线失效通知 + R4 显式代次记录一起修掉。
- **Rationale**: 逐域最小改动，全部复用既有加载器与一致性检查；agents 域对账式重载天然覆盖「副本重启后按当前盘面重建」（spec US1 场景 5）。
- **Alternatives considered**: 版本号带「变更明细」（哪个 Agent 变了）做增量重载——需要写路径与总线间的明细协议，收益仅省一次扫盘（几十目录、毫秒级），YAGNI 拒；全域统一「重启式全量 loadAll 换新 Registry」——ProfileRegistry 被 AgentScheduler 等多处持有引用，整体换对象要动装配面，拒。

## R3. watcher 与 poller 的档位切换

- **Decision**: 集群档（`cluster.enabled=true`）**不装配** `WorkspaceWatcher` 与 `KnowledgeWatcher`（OryxOsRuntime 条件装配），改装 `WorkspaceVersionPoller`；单机档反之，装配面零改动。知识目录的 GitOps 语义在集群档由「上传 API + 手动刷新入口」承接（直接改盘 → 刷新入口触发 knowledge 域重载 → `KnowledgeIndexService.reconcile` 指纹对账，既有幂等逻辑复用）。
- **Rationale**: inotify/WatchService 在 NFS 上不产生事件是本刀的出发点之一；两套感知机制并存会产生双触发。规划裁决「单机档保留 watcher 零回归」。
- **Alternatives considered**: 集群档 watcher + poller 双开兜底——NFS 上 watcher 静默无事件，留着只增加「看似生效实则未挂」的误判面，拒。

## R4. 知识索引恰好一次与代次提交

- **Decision**: 三件套，全部复用 026 CAS 形态：
  1. **认领**：新表 `knowledge_build_claims`（`kb_name` PK、`owner`、`lease_until`、`generation`），`CoordinationStore` 新增 `tryAcquireIndexBuild / renewIndexBuild / releaseIndexBuild`——语义与 channel 租约同构（唯一约束互斥 + 条件更新抢过期）；构建期间按批续租（与 turn 租约同款心跳纪律），续租失败立即中止本副本构建。
  2. **已提交代次显式化**：新表 `knowledge_generations`（`kb_name` PK、`committed_generation`、`updated_at`）。检索读已提交代次（每副本缓存 + 总线失效）；替换「max(generation) 推断」——同时修掉单机档「重建期间首次惰性推断读到构建中代次」的既有隐患。
  3. **条件提交**：重建完成的提交 = 同事务内「校验本副本仍持有认领（rowcount=1 条件更新 claim）+ 写 committed_generation + deleteGenerationsBelow + 递增 knowledge 域版本号」；认领已失（被接管）则丢弃本副本新代，不提交不删旧。
  - 单机档：认领与续租走 NOOP（cluster 关闭时零协调写），代次表照常使用（修隐患对单机同样有效，但行为口径不变：检索结果一致）。
- **Rationale**: 恰好一次的存储形态与 026 完全同构（零新机制承诺）；显式代次是集群下检索一致性的唯一可靠载体。
- **Alternatives considered**: 复用 `channel_leases` 表加 kind 列——026 刚裁决过每场景独立表（语义清晰、清理策略各异），跟随；构建状态机全量入协调表（进度、批次）——恢复语义是「接管重建」不是「断点续跑」，无需进度持久化，拒。

## R5. 原子写纪律的统一收口

- **Decision**: `oryxos-core` 新增 `AtomicFiles.write(path, bytes)` 工具（同目录临时文件写全 + `ATOMIC_MOVE`，不支持原子移动则抛异常——沿 `AgentStore.moveAtomic` 的「不降级」策略）。收口调查发现的四处非原子写：`SkillStore.write/writeAll`、`PersonaStore.write`、`KnowledgeApiController.upload` 落盘、`AgentStore.restore`。`AgentStore.writeAll` 既有两阶段实现保持不动（已达标且含备份回滚）。
- **Rationale**: spec FR-004/SC-004；共享卷上半写文件会被其他副本立即读到，单机档只是低概率脏读、集群档是必然事故。
- **Alternatives considered**: 沿 `MarkdownMemoryStore` 的「非原子 fallback」——共享卷场景降级等于静默放弃承诺，拒（该类自身属单机档记忆后端，不在本刀收口范围）。

## R6. 手动刷新入口

- **Decision**: `POST /api/v1/workspace/refresh`（挂既有 `WorkspaceApiController`）。集群档：全部域版本号 +1（所有副本下一轮轮询重载）；单机档：直接本地全量重载（agents reconcileAll + skills 重载 + knowledge reconcile），返回体报告触发方式。维护者裁决（2026-09-14，spec FR-011）。
- **Rationale**: 运维直接改盘的逃生舱；复用同一总线，无第二套通知机制。
- **Alternatives considered**: 仅文档裁决 / 重启生效——已由维护者否决。

## R7. fail-fast 新增组合与支持矩阵

- **Decision**: `ClusterStartupCheck` 本刀**不新增**拒启组合（026 三组合已覆盖文件面相关误配：markdown 记忆、memory 知识库；共享卷「是否真共享」无法从配置判定——按 spec 裁决走文档声明）。新增部署文档《共享卷支持矩阵》：依赖 close-to-open 一致性 + 同卷 rename 原子性；NFS 建议挂载参数（默认 close-to-open 即可，禁 `nolock` 无关紧要因为不依赖锁、禁关闭一致性的激进缓存参数）；K8s RWX PVC（NFS/CephFS 类）可用，本地盘多副本仅限同机验证。
- **Rationale**: 拒启检查只能判配置字符串（探索报告确认该类不注入业务 Bean 的约束）；卷语义探测既不可靠又有误判成本。
- **Alternatives considered**: 启动时跨副本写读探针文件——需要副本间时序协调，误判（慢 NFS）与漏判（同机双进程）都常见，拒。

## R8. 迁移与配置

- **Decision**: Flyway **V8** 双 vendor 各一份纯 SQL（三张新表均 CREATE TABLE，无 ALTER，SQLite 无需 Java 迁移类）：`workspace_versions`、`knowledge_build_claims`、`knowledge_generations`；`workspace_versions` 由迁移预插 4 行域记录（version=0）。新配置项挂 `ClusterProperties`：`workspace-poll-interval`（默认 1s）；索引认领 TTL 复用既有 `lease-ttl`（30s + 续租，不另立参数）。
- **Rationale**: V7 之后即 V8（探索报告确认双轨现状）；「V6 起非幂等干净 SQL」规则适用。
- **Alternatives considered**: 每域独立轮询间隔——无场景差异，拒。

## R9. 指标

- **Decision**: 索引认领复用既有 `recordLeaseAcquired / recordLeaseReclaimed / recordFenceConflict`，kind 取 `"index"`（零新方法）；新增 `recordWorkspaceReloaded(String domain)` 一个方法（Micrometer 侧 `oryxos_workspace_reloads_total{domain=…}`，命名沿 026 风格）。顺手补上 026 遗留的 `recordLeaseAcquired("schedule")` 空缺埋点（调查发现 claimFireTime 赢者路径未埋）。
- **Rationale**: 低基数 tag、default 方法零破坏，全部沿既有纪律。
