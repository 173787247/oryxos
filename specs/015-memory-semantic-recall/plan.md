# Implementation Plan: 记忆检索升级与后端契约对齐

**Branch**: `015-memory-semantic-recall` | **Date**: 2026-08-20 | **Spec**: [spec.md](spec.md)

**Input**: 定稿 spec（18 FR / 11 SC / 5 US，三轮 clarify 共 14 问全部拍板）、[research.md](research.md)（D1~D8）。

## Summary

`recall_memory` 从「归档区纯关键词」升级为「语义 + 关键词 + 时间新近」三路加权 RRF 融合（权重
配置系数、缺省等权），复用 014 检索基建——纯函数融合层随本特性上移 core（014 plan 预告的时机）。
记忆后端契约对齐 014 插件范式：检索能力三态声明 + 分区必选（装配期校验）+ 参数化契约测试；
mem0 档按「core 原文模式 + archival 交提炼 + 元数据分区过滤」真实跑通（docker compose 可复现，
integration 标签）。写入侧条目落库优先、有界异步向量化、启动幂等对账。sqlite 档补 agent_name
列修复作用域缺口。全程未配置向量化 = 现状行为（除大小写统一）。

## Technical Context

**Language/Version**: Java 21（虚拟线程）；无前端改动

**Primary Dependencies**: 零新增 Maven 依赖——TextEmbedder / mock 向量 / RRF / 向量编解码全部复用
014 已交付基建；mem0 联调走既有 RestClient

**Storage**: SQLite 新增 `memory_vectors` 表（向量 BLOB，schema.sql 手工 DDL）；`memory_entries`
补 `agent_name` 列（照 ScheduleSchemaUpgrade 的 PRAGMA + ALTER ADD COLUMN 先例，幂等）

**Testing**: JUnit 5 + Mockito + 参数化契约测试（markdown / sqlite / DELEGATED 桩三档常驻 CI；
mem0 真实档 @Tag("integration")）；docker compose 起 mem0 OSS 供联调

**Target Platform**: Linux/macOS 单机（markdown 档单机定位；sqlite 档为分布式主路径）

**Performance Goals**: 万条归档条目单次检索 ≤ 1s（SC-008）；存量补索引 30s 窗口（SC-006）

**Constraints**: 同步执行 + 虚拟线程（索引异步段为有界执行器跑同步代码，D6 采纳 AgentScope 有界
调度）；工具签名与未配置行为零变化（SC-002/003）；记忆写入零丢失（SC-007）

**Scale/Scope**: 单 Agent 归档万条内；涉及 `oryxos-core`（纯函数上移 + 契约枚举）、`oryxos-memory`
（引擎与后端）、`oryxos-storage`（表/列/仓库）、`oryxos-knowledge`（改 import）、`oryxos-cli`（装配）；
**不新建模块**

## Constitution Check

### 设计前门禁

- **I 自实现 ReAct Loop**: PASS。`recall_memory` 仍是普通工具经 ToolExecutor 调度；循环零改动。
- **II Spring AI 使用边界**: PASS。向量化继续走 014 的 TextEmbedder 端口（provider 侧协议转换），
  无新框架面。
- **III Provider 显式映射**: PASS。embedding 供给沿用按名注册表解析；记忆后端档按 `memory.backend`
  显式 switch 装配（现状机制），能力声明装配期校验。
- **IV 目录 Agent / 渐进披露**: PASS。注入策略零改动（核心区全量 + 归档窗口照旧）；不新增每轮
  自动检索（spec 明确不做）。
- **V 审计 Day One**: PASS。recall_memory 照旧经 ToolExecutor 落 `tool_invocations`；FR-009 四要素
  的落位见「Runtime Design / 审计埋点」——查询原文（input_json）、命中明细（result 文本行）、
  耗时（duration_ms 列）、降级标记（结果尾行，仅降级时出现）。
- **VI 安全**: PASS。无新凭证（embedding 复用注册表；mem0 连接经环境变量）；无新文件面；Agent 侧
  不提供删除记忆工具（spec 拍板，防注入抹除）。
- **VII 同步执行**: PASS。契约全同步签名；异步仅限「落库后的向量化补齐」——有界单工作线程执行器
  跑同步代码，不引入 Reactor/CompletableFuture 编程模型（与 014 索引后台段同款）。
- **VIII 状态外置**: PASS。向量索引落 SQLite 与本体同库、派生可重建、对账幂等（FR-016 分布式
  约束逐条落地）；表结构手工 DDL + 幂等升级器，不用 Hibernate 迁移。

### 宪法停点声明（三项）

1. **不新建模块**：记忆检索引擎、后端契约与 mem0 适配归 `oryxos-memory`（契约仅该模块实现与
   消费，无跨模块依赖倒置需求，不上移 core）；模块表无需变更。
2. **检索纯函数基建上移 core**：`RetrievalPipeline`（RRF 融合 + 余弦，纯函数零依赖）从
   `oryxos-knowledge` 移至 `oryxos-core/io/oryxos/core/retrieval/`，并扩展**加权** RRF 重载；
   `oryxos-knowledge` 改 import——这正是 014 plan 预告的「上移时机由方向 B 触发」，须同步
   `docs/TechnicalSolution.md` 相应描述（CLAUDE.md 模块表无变化，不需改）。
3. **新表与新配置键**：`memory_vectors`（schema.sql 手工 DDL）；`memory_entries` 加 `agent_name`
   列（新装 schema.sql 全量含列，存量库经 `MemorySchemaUpgrade` PRAGMA 检测 + ALTER 幂等补列，
   存量行归 `__global__` 占位）；配置键：全局 `embedding.provider/model`（缺省回读
   `knowledge.embedding.*` 兼容别名）、`memory.recall.weight.semantic/keyword/recency`
   （double，缺省各 1.0）、mem0 档沿用既有 `memory.mem0.*` 段并新增 `memory.mem0.api-key`
   （环境变量引用）。

### Phase 1 设计后复核

- 条目标识 = `sha256(agent|scope|条目行原文)`——跨三档统一、无需改本体存储格式；markdown 档条目
  自带时间戳（`- [yyyy-MM-dd HH:mm] …`），时间路解析它，sqlite 档用 created_at。
- 关键词路以**本体**为准（覆盖未索引条目——追齐窗口零召回损失，Edge Case 拍板）；语义路以
  `memory_vectors` 为准；时间路以本体为准；三路经条目哈希对齐后加权 RRF。
- DELEGATED 档（mem0）整体接管 recall；`memory_vectors` 不为其建行；分区经 metadata 映射
  （core=infer:false 原文 / archival=infer:true 提炼），装配期能力校验统一收口。
- 未配置 embedding 时语义路静默缺席（供给者抛可读异常→引擎按降级处理），关键词 + 时间两路照跑
  ——SC-002 的字节级兼容由「未配置 ⇒ 权重与旧行为等价」的专项 CI 断言钉死。
- 契约修订文档同步：`LongTermMemoryStore` javadoc 契约四改写 + CLAUDE.md「Memory」描述行微调，
  随实现 PR 一并提交（FR-012）。

结论：全部门禁通过；停点三项已声明，无需复杂度豁免。

## Project Structure

### Documentation (this feature)

```text
specs/015-memory-semantic-recall/
├── spec.md / research.md / checklists/requirements.md
├── plan.md                  # 本文件
├── data-model.md
├── quickstart.md
├── contracts/
│   └── memory-backend.md    # 后端契约 + 行为不变量 + 工具行为
└── tasks.md                 # 下一阶段 speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── retrieval/RetrievalPipeline.java     # 自 oryxos-knowledge 上移 + 加权 fuseByRank 重载（停点 2）
└── memory/
    ├── MemoryRecallCapability.java      # 枚举：KEYWORD / HYBRID_BUILTIN / DELEGATED
    └── MemoryEntryView.java             # 条目视图（内容 + 时间），时间路/索引对账的取数形状

oryxos-memory/src/main/java/io/oryxos/memory/
├── LongTermMemoryStore.java             # 契约升级：+capabilities() +archivalEntries()；javadoc 契约四修订
├── MemoryRecallEngine.java              # 三路召回 + 加权 RRF（HYBRID_BUILTIN 档共用）
├── MemoryVectorIndex.java               # memory_vectors 读写 + 有界异步向量化 + 幂等对账/重建
├── MemoryServiceImpl.java               # recall 按能力路由：DELEGATED 直通 / 其余走引擎
├── MarkdownMemoryStore.java             # +capabilities +archivalEntries；关键词不区分大小写
├── SqliteMemoryStore.java               # 同上 + agent_name 作用域（读写均带）
└── Mem0MemoryStore.java                 # DELEGATED：core=infer:false / archival=infer:true、
                                         #   分区过滤实测、api-key 头、可读故障语义

oryxos-knowledge/.../retrieve/           # RetrievalPipeline 移除，import 改指 core
oryxos-storage/
├── src/main/java/io/oryxos/storage/{MemoryVectorEntity,MemoryVectorRepository}.java
├── src/main/java/io/oryxos/storage/{MemoryEntry(+agentName),MemoryEntryRepository(+按agent查询)}.java
├── src/main/java/io/oryxos/storage/MemorySchemaUpgrade.java   # PRAGMA+ALTER 幂等补列（照 Schedule 先例）
└── src/main/resources/schema.sql        # +memory_vectors；memory_entries 全量含 agent_name

oryxos-cli/.../OryxOsRuntime.java        # embedding.* 全局键（旧键回退）、权重配置、
                                         #   MemoryVectorIndex/引擎装配、mem0 档参数、SchemaUpgrade 接线
docker/mem0/compose.yaml                 # mem0 OSS 联调环境（FR-018，可复现）
各模块 src/test：契约参数化（3 档常驻 + mem0 @Tag integration）、引擎/索引/对账单测、兼容性 CI 断言
```

**Structure Decision**: 引擎与契约留 `oryxos-memory`（无跨模块消费，不为对称而上移）；只有纯函数
融合层上移 core（知识与记忆真实共用）；存储照 014 分工进 storage；装配唯一落点 OryxOsRuntime。

## Runtime Design

### 三路召回与融合（FR-001/002/003，SC-002/003/004）

1. 入口不变：`MemoryTools.recallMemory(keyword)` → `MemoryService.recall`。`MemoryServiceImpl`
   按当前档能力路由：`DELEGATED` → 直通 store（mem0 语义检索，scope=ARCHIVAL 过滤）；
   `HYBRID_BUILTIN`/`KEYWORD` → `MemoryRecallEngine`。
2. 引擎三路（均限归档区、按当前 Agent 圈定）：
   - **关键词路**：`store.recallByKeyword`（统一 `toLowerCase(ROOT)` 包含匹配，FR-002）；
   - **语义路**：`TextEmbedder.embed(query)` → `memory_vectors`（agent 过滤）余弦，模型标识不一致
     的行不参与（对账会重建）；embedding 供给异常 ⇒ 本路缺席并标注降级；
   - **时间新近路**：`store.archivalEntries()` 按时间倒序取前 N。
3. **加权 RRF**：`score(e)=Σ wᵣ/(K+rankᵣ)`，权重来自 `memory.recall.weight.*`（缺省 1.0）；三路
   候选经条目哈希对齐。返回条目原文列表（形态与现状一致）；降级时结果末尾追加一行
   `（语义检索暂不可用，已按关键词与时间返回）`——未配置向量化模式下**不追加**（保 SC-002 字节级
   兼容），仅审计可见常态降级。
4. topK 沿用现状返回全部命中？现状 recall 返回全部匹配行——引擎统一取融合后前 20（配置
   `memory.recall.top-k`，缺省 20，覆盖现状典型规模），未配置向量化时为保兼容**不截断**。

### 写入与索引（FR-005/006/007，SC-006/007）

1. `remember()`：本体落库路径零改动（先写，永不因索引失败而失败）；落库成功后向**有界执行器**
   （单工作线程 + 有限队列，队满丢弃任务——对账兜底，D6）投递向量化：写 `memory_vectors`
   （entry_hash/agent/scope/content/embedding/dim/model/entry_time）。
2. 启动对账 `MemoryVectorIndex.reconcile()`（幂等，多副本安全）：逐 Agent 比对本体归档条目哈希
   集与索引行——缺失补向量、孤儿删行、模型标识≠当前配置的行整体重建；markdown 档 Agent 清单来自
   `agents/*/MEMORY.md` 扫描 + 全局文件，sqlite 档来自 distinct agent_name。
3. `MemorySchemaUpgrade`：PRAGMA 检测 `memory_entries.agent_name`，缺则 ALTER ADD COLUMN
   DEFAULT `__global__`（存量归全局占位，可读迁移说明打启动日志一次）。

### mem0 档跑通（FR-017/018，US5/SC-011）

- 写：core → `POST /v1/memories/ {infer:false, metadata:{scope:CORE}}`（原文保真）；archival →
  `{infer:true, metadata:{scope:ARCHIVAL}}`（交提炼）。
- 读：load() 两区分别 `get_all` + metadata 过滤；recall → `search` + `filters:{scope:ARCHIVAL}`。
  过滤有效性联调实测（含 issue #3773 的版本行为核验，若 v2 filter 失效则回退 v1 参数式过滤并在
  适配器注释记录版本边界）。
- 故障：连接异常 → 可读 IllegalStateException（工具层转可读结果入审计，对话不中断）；写失败
  MUST 可读呈现（mem0 档无本地兜底，DELEGATED 语义如实暴露）。
- 环境：`docker/mem0/compose.yaml`（mem0 OSS + 内置向量库），LLM/embedder 指向 OpenAI 兼容端点
  （联调用 zhipu key，经环境变量）；集成测试 @Tag("integration")，契约桩档常驻 CI。

### 契约与测试（FR-009~012，SC-009/010）

- `LongTermMemoryStore` 升级：`capabilities()`（MemoryRecallCapability + 分区语义为隐含必选——
  接口的 scope 参数即分区，DELEGATED 档以映射兑现；无法兑现的实现装配期抛可读异常）、
  `archivalEntries()`（DELEGATED 档返回空列表——引擎不会调它）。
- `MemoryBackendContractTest` 参数化四档（markdown / sqlite / DELEGATED 桩 / mem0-integration）：
  写入即可见、核心区不截断、scope 显式、per-Agent 隔离、大小写统一、降级可读、时间序正确。
- 兼容性专项：`RecallBackwardCompatTest`——未配置 embedding 时，对同一数据集断言与「旧算法参考
  实现」输出逐字节一致（除大小写统一），锁死 SC-002/003。

## Delivery Order（单 PR 内的提交切分建议）

1. 基建上移：RetrievalPipeline → core + 加权重载 + knowledge 改 import（全量回归）；
2. 存储：schema.sql（memory_vectors + memory_entries 含列）、实体/仓库、MemorySchemaUpgrade；
3. 契约与引擎：LongTermMemoryStore 升级、MemoryRecallEngine、MemoryVectorIndex（含对账）、
   MemoryServiceImpl 路由、两本地档改造、契约测试 + 兼容性专项；
4. mem0：适配器改造（infer/metadata/过滤/故障）、compose 清单、integration 测试；
5. 装配与文档：OryxOsRuntime 配置键与 bean、javadoc 契约修订、CLAUDE.md 一行、
   application.yml.example、README。

## Complexity Tracking

无宪法违例。RetrievalPipeline 上移是 014 已预告的计划内动作（触发条件成立）；不新建模块；
`MemoryRecallEngine` 与 `MemoryVectorIndex` 是本特性的最小职责拆分，不构成额外抽象层。
