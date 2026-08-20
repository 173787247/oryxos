# Tasks: 记忆检索升级与后端契约对齐

**Input**: `spec.md`（18 FR / 11 SC / 5 US）、`plan.md`（三停点）、`data-model.md`、
`contracts/memory-backend.md`、`quickstart.md`

**Tests**: 契约 9 条行为不变量参数化钉死（三档常驻 CI + mem0 @Tag integration）；SC-002/003 由
兼容性专项对照测试锁死；全程测试先行。

**Organization**: Phase 1~2 为全阻塞地基（基建上移 → 存储）；Phase 3 交付本地档三路检索
（US1/US2 主体）；Phase 4 契约桩与兼容专项（US3/SC-002）；Phase 5 mem0 跑通（US5）；
Phase 6 装配、mock 全链路（US4）与文档收口。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可与同阶段其它标记任务并行（不同文件、无未完成依赖）。
- **[Story]**: 对应 `spec.md` 的 US1~US5。

## Phase 1: Setup — 检索基建上移 core（宪法停点 2）

- [ ] T001 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/retrieval/RetrievalPipelineTest.java`
      新建失败测试：加权 `fuseByRank`（等权结果 = 现有无权重版逐项一致；权重 2:1:1 时高权路
      候选名次提升；空路/零权重容忍）、余弦维度拒绝（迁移原有断言）
- [ ] T002 把 `RetrievalPipeline` 从 `oryxos-knowledge/.../retrieve/` 移至
      `oryxos-core/src/main/java/io/oryxos/core/retrieval/RetrievalPipeline.java`，新增加权重载
      `fuseByRank(int topK, double[] weights, List<Candidate>... routes)`（无权重版委托等权调用，
      行为不变）（T001 转绿）
- [ ] T003 `oryxos-knowledge` 改 import（`LocalKnowledgeBackend` 与相关测试）、删除原类与旧测试
      文件；`mvn test -pl oryxos-core,oryxos-knowledge -am` 全量回归零变化

---

## Phase 2: Foundational — 存储（宪法停点 3，阻塞所有故事）

**⚠️ CRITICAL**: 本阶段完成前不得开始 Phase 3+。

- [ ] T004 [P] `oryxos-storage/src/main/resources/schema.sql`：追加 `memory_vectors` DDL
      （data-model §2：entry_hash/agent_name/content/embedding/dim/embedding_model/entry_time +
      UNIQUE(agent_name, entry_hash) + idx）；`memory_entries` 建表语句全量补 `agent_name`
      VARCHAR(128) NOT NULL DEFAULT '__global__' + `idx_memory_agent`
- [ ] T005 [P] 在 `oryxos-storage/src/main/java/io/oryxos/storage/` 创建
      `MemoryVectorEntity.java` 与 `MemoryVectorRepository.java`（findByAgentName、
      findByAgentNameAndEntryHash、deleteByAgentNameAndEntryHashIn、deleteByEmbeddingModelNot；
      派生 delete 一律 `@Transactional(rollbackFor = Exception.class)`）
- [ ] T006 `MemoryEntry.java` 补 `agentName` 字段；`MemoryEntryRepository.java` 全部查询改带
      agent 维度（findByAgentNameAndScopeOrderByIdAsc/Desc、searchArchival 带 agent 参数），
      调用方编译同步
- [ ] T007 在 `oryxos-storage/src/main/java/io/oryxos/storage/MemorySchemaUpgrade.java` 照
      `ScheduleSchemaUpgrade` 先例实现幂等补列（PRAGMA table_info 检测 → ALTER ADD COLUMN
      agent_name DEFAULT '__global__' + 建索引 + 一次性可读迁移日志）；配套
      `MemorySchemaUpgradeTest.java`（旧结构库升级、重复执行幂等）

**Checkpoint**: 新装/存量两种库形态均可启动，地基就绪。

---

## Phase 3: 本地档三路检索（US1 + US2 主体，P1）🎯 MVP

**Goal**: markdown/sqlite 档的 recall 升级为三路加权融合；写入零丢失；存量对账；未配置零变化。

**Independent Test**: quickstart §A（未配置零变化）/ §C（语义 + 时间）/ §D（降级与零丢失）/
§E（sqlite 作用域）。

### Tests

- [ ] T008 [P] [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/memory/` 创建
      `MemoryRecallCapability.java`（枚举）与 `MemoryEntryView.java`（record，content+time）
      ——纯类型无行为，直接实现（无独立测试）
- [ ] T009 [US1] `oryxos-memory/.../LongTermMemoryStore.java` 契约升级：+`capabilities()`
      +`archivalEntries()`；javadoc 契约四修订为「三路可降级，降级态即统一后的关键词行为」
      （FR-010/012）
- [ ] T010 [P] [US1] 在 `oryxos-memory/src/test/java/io/oryxos/memory/MarkdownMemoryStoreTest.java`
      补失败测试：`archivalEntries()` 解析 `- [yyyy-MM-dd HH:mm]` 时间戳（解析失败 time=null）、
      `recallByKeyword` 不区分大小写（FR-002）、仅归档区
- [ ] T011 [US1] `MarkdownMemoryStore` 实现 T010（capabilities=HYBRID_BUILTIN）
- [ ] T012 [US1] `SqliteMemoryStore`：读写全部带 agent_name（经 ToolExecutionContext）、
      `archivalEntries()`（created_at 为 time）、大小写不区分确认、capabilities=HYBRID_BUILTIN；
      补 `SqliteMemoryStoreTest` agent 隔离断言（US1 场景 6 / FR-014）
- [ ] T013 [P] [US2] 在 `oryxos-memory/src/test/java/io/oryxos/memory/MemoryVectorIndexTest.java`
      新建失败测试：落库后异步入队向量化（direct executor）、**core 条目不入队不入表（仅归档
      向量化，FR-005）**、embedder 异常不抛出不阻塞（零丢失，FR-005）、队满静默丢弃、
      `reconcile()` 补缺失/清孤儿/模型变更整体重建、重复执行幂等（FR-007）
- [ ] T014 [US2] 在 `oryxos-memory/src/main/java/io/oryxos/memory/MemoryVectorIndex.java` 实现：
      entry_hash=sha256(agent|scope|行原文)、有界执行器（1 worker + 有限队列，构造可注入 direct
      executor 供测试）、写 `memory_vectors`、`reconcile(agentName, List<MemoryEntryView>)`
      （T013 转绿）
- [ ] T015 [P] [US1] 在 `oryxos-memory/src/test/java/io/oryxos/memory/MemoryRecallEngineTest.java`
      新建失败测试：三路融合（语义命中措辞不同条目 / 关键词命中精确代号 / 相关性相当时较新者
      排前）、权重系数生效（recency 权重调大改变排序，SC-004）、未配置 embedding = 两路且输出
      与旧格式等价、降级尾行仅配置态出现（FR-003/013）、top-k 截断仅配置态生效
- [ ] T016 [US1] 在 `oryxos-memory/src/main/java/io/oryxos/memory/MemoryRecallEngine.java` 实现
      三路加权 RRF（复用 core `RetrievalPipeline`；候选按 entry_hash 对齐）（T015 转绿）
- [ ] T017 [US1] `MemoryServiceImpl`：recall 按 `capabilities()` 路由（DELEGATED 直通 / 其余走
      引擎）；`remember()` 落库成功后**仅 archival 条目**入队 `MemoryVectorIndex`（core 不入索引，
      FR-005）；启动对账入口方法；补路由单测

**Checkpoint**: 本地两档三路检索、零丢失、对账全绿——MVP 达成。

---

## Phase 4: 契约钉死与兼容锁（US3 契约面 + SC-002/003）

- [ ] T018 [P] [US3] 在 `oryxos-memory/src/test/java/io/oryxos/memory/contract/MemoryBackendContractTest.java`
      参数化契约测试（markdown / sqlite / DELEGATED 桩三档常驻 CI）：契约 9 条行为不变量
      （contracts §2）逐条断言；桩 = 自带语义的假后端（可配置命中与故障）；外加负例——
      无法兑现分区语义的坏桩 MUST 在装配期被可读拒绝、无降维路径（SC-009）
- [ ] T019 [P] [US2] 在 `oryxos-memory/src/test/java/io/oryxos/memory/RecallBackwardCompatTest.java`
      兼容性专项：同一数据集上，未配置 embedding 的新实现输出与「旧算法参考实现」（内嵌于测试）
      逐字节一致（除大小写统一修正），锁死 SC-002/003

---

## Phase 5: mem0 档真实跑通（US5，P2）

- [ ] T020 [P] [US5] 新建 `docker/mem0/compose.yaml`（mem0 OSS + 依赖存储；LLM/embedder 经
      OpenAI 兼容端点环境变量注入，含使用说明注释：`ZHIPU_API_KEY` 复用）；`.gitignore` 确认
      不吞该目录
- [ ] T021 [P] [US5] 在 `oryxos-memory/src/test/java/io/oryxos/memory/Mem0MemoryStoreTest.java`
      新建失败测试（MockRestServiceServer/自建桩 server）：core 写入带 `infer:false` +
      metadata scope、archival 带 `infer:true`、search 带 `filters:{scope:ARCHIVAL}`、
      get_all 按区过滤、`Authorization: Bearer` 头（配置为空不发头）、连接异常 → 可读
      IllegalStateException、capabilities=DELEGATED、archivalEntries 返回空
- [ ] T022 [US5] `Mem0MemoryStore` 按 contracts §3 映射表改造（T021 转绿）；`memory.mem0.api-key`
      配置接入
- [ ] T023 [US5] 在 `oryxos-memory/src/test/java/io/oryxos/memory/Mem0FlowIT.java`
      （@Tag("integration")）：真实 compose 环境跑 US5 四场景——core 原文逐字保真与注入、
      archival 提炼后语义命中、分区过滤实测（#3773 版本核验，失效则适配器回退 v1 过滤并记录）、
      停机可读降级
- [ ] T024 [US5] `MemoryBackendContractTest` 增挂 mem0 档参数分支（@Tag("integration")），
      九条不变量全绿（SC-010/011）

---

## Phase 6: 装配、mock 全链路与收口（US4 + 文档）

- [ ] T025 `oryxos-cli/.../OryxOsRuntime.java` 装配：全局 `embedding.provider/model`
      （`${embedding.provider:${knowledge.embedding.provider:}}` 嵌套缺省回读旧键，知识侧同步
      改读全局键）、`memory.recall.weight.*`/`top-k` 配置、`MemoryVectorIndex`/引擎 bean 与
      启动对账、`MemorySchemaUpgrade` @DependsOn 接线、mem0 api-key；`MemoryTools.saveMemory`
      的 scope 参数描述按 FR-008 文案更新
- [ ] T026 [P] [US4] 在 `oryxos-boot/src/test/java/io/oryxos/boot/MemoryRecallFlowIT.java`
      （@Tag("integration")）：mock 整机走通「save（core+archival）→ 启动对账 → 三路 recall
      确定性复检 → 降级演练 → agent 隔离」（SC-005 CI 断言路径参照 KnowledgeFlowIT）
- [ ] T027 [P] 文档同步：`docs/TechnicalSolution.md`（RetrievalPipeline 上移 core 一句 +
      Memory 检索描述 + **markdown 记忆档显式定位为单机档**，FR-016）、`CLAUDE.md` Memory
      行微调、`config/application.yml.example`
      （全局 embedding.* 段 + memory.recall.* + mem0 api-key 注释）、`README.md` Memory 能力行
- [ ] T028 全量收口：`mvn verify` 全 reactor 门禁 + 四档契约绿 + quickstart §A~E 开发侧自测；
      §F（mem0 compose）在 docker 就绪环境跑通
- [ ] T029 维护者统一验收：quickstart A~G 与 SC-001~011 勾验（SC-001 为人工评审软指标；
      SC-008 万条归档 ≤1s 在验收环境实测，quickstart §G 有步骤）

---

## Dependencies & Execution Order

- **Phase 1 → 2 → 3 严格串行**；Phase 4 依赖 Phase 3（契约测试挂真实现）；
  Phase 5 依赖 Phase 3 契约升级（T009）+ Phase 4 桩形状（T018）；Phase 6 依赖前序全部。
- Phase 3 内：T008/T009 先行；T010~T012（档改造）与 T013/T014（索引）可并行；
  T015/T016（引擎）依赖 T014；T017 收口。
- **单 PR 交付**（照 014 惯例，验收通过后一并提交）；提交切分建议 = plan「Delivery Order」五段。

每段合并前：新增测试全绿 + `mvn verify` 通过 + 提交信息标注对应 FR/SC 编号。
