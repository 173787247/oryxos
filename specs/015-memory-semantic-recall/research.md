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

## 外部参考

- 014 检索基建：`oryxos-core/embedding/TextEmbedder`、`oryxos-knowledge/retrieve/RetrievalPipeline`、
  `store/ChunkStore` + Sqlite/InMemory 两档
- 记忆四条行为契约：`oryxos-memory/LongTermMemoryStore` javadoc（契约四将由 FR-010 修订）
- 路线图方向 B（记忆语义化复用检索基建）：docs/roadmap 及 014 spec FR-016 / research D10
