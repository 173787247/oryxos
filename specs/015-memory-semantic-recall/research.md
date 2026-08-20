# Research: 记忆语义检索（Memory Semantic Recall）

**Created**: 2026-08-19　**Feature**: [spec.md](spec.md)　**Phase**: 前置调研（供 /speckit-plan 消费）

延续 006/014 的决策编号惯例。核心事实来自代码现状核查（2026-08-19，main + PR #205 分支）。

---

## D1. 复用形态：TextEmbedder / RRF 直接复用；向量存储「适配 vs 泛化」留 plan 评审

**现状核查**：
- `io.oryxos.core.embedding.TextEmbedder`（014 上移 core 的通用端口）与
  `RetrievalPipeline.fuseByRank/cosine`（纯函数，无业务语义）——**零改动直接复用**。
- 向量存储 `ChunkStore` 接口形状偏文档（kbName/documentId/relPath/generation/pageNo）——记忆条目
  没有文档层级。两条路：①适配复用：以 `kbName = "memory:<agent>"`、`relPath = 条目id` 映射进现表
  （零新表，但语义别扭、看板归属需排除 memory: 前缀）；②泛化接口：抽出条目级 `VectorIndexStore`
  （id/namespace/向量/模型），知识与记忆各自适配（干净，但动 014 刚交付的接口）。
- **plan 停点评审**，倾向②的轻量版：新表 `memory_vectors`（条目id/agent/向量/模型/维度），复用
  编解码与余弦，不动 ChunkStore——改动面最小且语义各归各位。

**记忆检索与知识检索的分层关系**（D10 分层统一的延续）：语义层继续分离（`recall_memory` /
`retrieve_knowledge` 两个工具、两套门面），基建层共享（embedder、融合、编解码）。

**三路修订（2026-08-20）**：记忆与知识的本质差异是**时间性**——「上次那个问题」携带时间信号，
经典 Generative Agents 用 relevance×recency×importance 三因子，Letta/Zep/mem0 检索均带时间权重。
RRF 按名次融合天然支持多路：把「写入时间倒序」作为第三路参与融合，零调参、不改工具契约，
即补齐这个维度。降级态 = 关键词 + 时间两路（仍优于纯关键词现状）。

## D2. 写入一致性与知识相反：条目落库优先、索引异步补齐

知识导入允许显式失败（FR-013）——源文件还在磁盘上，管理员可重试。记忆写入是**对话过程的副作用**：
`save_memory` 失败即永久丢失该记忆。因此一致性取舍必须反过来：

1. `remember()` 先走既有落库路径（三档 store 不动）；
2. 向量化在落库后异步进行（虚拟线程），失败仅记「待索引」；
3. 启动/定期对账补齐（与 014 reconcile 同款幂等思路，按条目标识 + 模型标识判差异）。

推论：记忆索引永远允许「暂时落后于本体」，检索契约按「已索引部分走语义路 + 全量走关键词路」
融合——关键词路天然覆盖未索引条目，**降级路径同时就是追齐窗口的兜底**，无一致性窗口丢召回。

## D3. mem0 档直通

mem0 本身是语义记忆服务（自带向量检索）。本地再建一层向量索引 = 重复建设 + 双份状态。拍板：
mem0 档的 `recallByKeyword` 升级为透传其检索接口（其结果天然语义化），不产生本地 `memory_vectors`
行；行为契约测试参数化覆盖三档（与 014 后端契约测试同法）。

## D4. embedding 配置键归属（clarify 问题 1 的证据）

现状：`knowledge.embedding.provider/model`（014 引入）+ `knowledge.store`。记忆复用同一 embedder
supplier（OryxOsRuntime 已是独立 `knowledgeEmbedderSupplier` bean，改名/别名成本≈0）。两案：
- 沿用原键：零迁移，文档注明「知识与记忆共用」；键名语义欠准。
- 新增 `embedding.provider/model` 全局段 + 旧键兼容读取：语义准确，多一段兼容代码与一次沟通。
倾向后者（趁只有一个消费特性时改名，越晚越贵），交维护者拍板。

## D5. 现状缺口备案：sqlite 档 memory_entries 无 Agent 维度

代码核查：`memory_entries(id, scope, content, created_at)`——无 `agent_name` 列；而 markdown 档
经 `ToolExecutionContext.agentName()` 落到 `agents/<name>/MEMORY.md`（per-agent）。即 **sqlite 档
的记忆疑似跨 Agent 共享**，与「记忆跟 Agent 走」（30 节）不一致。语义索引必须带 Agent 维度，
这个缺口绕不过去（clarify 问题 2）：随本特性补列（手工 DDL + 存量行归属策略）或只在索引层带维度。

## D6. 业界对标（2026-08 调研）：两条路线各占一档，不二选

**路线格局**：①专业记忆服务——mem0（48k stars/$24M A 轮，LLM 提取 + ADD/UPDATE/DELETE/NOOP 冲突
消解 + 向量/图混合检索）、Zep/Graphiti（时序知识图谱）、Letta（MemGPT 系，main/recall/archival
三层——OryxOS 核心区/归档区与之同源）、LangMem；②文件式记忆——Anthropic memory tool（/memories
文件目录，agentic 读写）与 Claude Code 的 CLAUDE.md 体系；③框架层全外包——AgentScope（零本地
实现，接 mem0/百炼/ReMe；AGENT_CONTROL/STATIC_CONTROL/BOTH 三模式）。

**采纳**：有界异步写入（AgentScope 自动 record 用 1 worker + 3 队列的有界调度，佐证 FR-005 的
异步补齐，plan 落 bounded executor）；时间权重共识（→三路融合）；能力声明式插件挂载（我们自己
014 的范式）。

**规避**：①提取式自动记忆——mem0 每次写入一次 LLM 判断（延迟 + 失败面 +「坏 UPDATE 静默改写」），
且 DELETE 后条目从检索彻底消失、审计要先知道 memory_id 才能查 history——与「可审计」核心主张
冲突；我们 **显式 save_memory = 高信噪比**，不需要提取也能 work（mem0 的提取是为「自动记录全部
对话」场景的噪音而生）。②每轮自动语义注入（STATIC/BOTH）——与 014 拒 GENERIC 同理，且核心区
全量注入已承担常驻职责。③响应式契约（宪法 VII）。

**定位结论**：markdown 档 ≈ 文件式路线（可读/可 Git/审计天然），015 用共享基建补齐其检索短板；
DELEGATED 档 ≈ 记忆服务路线（提取/冲突消解交给专业服务）。两条业界路线各占一档，用户按需选。

## D7. 分布式就绪（路线图方向 A 的对齐）

roadmap 原话：「记忆/调度/审计全外置共享 DB，实例彻底无状态」。015 以约束落地（FR-016）：
索引与本体同库（sqlite 档随共享 DB 走）、派生可重建、对账幂等（多副本并发收敛）、无实例内状态；
markdown 档显式定位单机（文件不跨副本共享）。FR-014 的 agent_name 补列因此升格为分布式主路径
前提。向量层扩容沿用知识的既定路线：SQLite BLOB → pgvector（roadmap「向量库两步走」）。

## 外部参考

- 014 检索基建：`oryxos-core/embedding/TextEmbedder`、`oryxos-knowledge/retrieve/RetrievalPipeline`、
  `store/ChunkStore` + Sqlite/InMemory 两档
- 记忆四条行为契约：`oryxos-memory/LongTermMemoryStore` javadoc（契约四将由 FR-010 修订）
- 路线图方向 B（记忆语义化复用检索基建）：docs/roadmap 及 014 spec FR-016 / research D10
