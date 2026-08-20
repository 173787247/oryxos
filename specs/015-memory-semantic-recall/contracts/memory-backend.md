# Contract: 记忆后端契约与工具行为

**Date**: 2026-08-20　**Feature**: [spec.md](../spec.md)　**落位**: `oryxos-memory`（契约枚举/视图在 `oryxos-core/memory`）

## 1. 契约形状（签名以实现 PR 为准）

```java
/** 检索能力三态（core/memory）。 */
public enum MemoryRecallCapability { KEYWORD, HYBRID_BUILTIN, DELEGATED }

/** 归档条目视图（core/memory）：时间新近路与索引对账的取数形状。 */
public record MemoryEntryView(String content, Instant time) {}

/** 升级后的后端契约（oryxos-memory）。分区语义为必选：append/load 的 scope 维度必须兑现，
 *  无法兑现的实现 MUST 在装配期抛可读异常（无降维路径，Clarify-R2）。 */
public interface LongTermMemoryStore {
  void append(String content, MemoryScope scope);          // 不变
  String load();                                            // 不变（核心全量 + 归档窗口）
  List<String> recallByKeyword(String keyword);             // 行为修订：跨档不区分大小写
  MemoryRecallCapability capabilities();                    // 新增
  /** 归档区全量条目（含时间）；DELEGATED 档返回空列表（引擎不会调它）。 */
  List<MemoryEntryView> archivalEntries();                  // 新增
}
```

路由规则（`MemoryServiceImpl.recall`）：

| capabilities() | recall 路径 |
|---|---|
| `DELEGATED` | 直通 `store.recallByKeyword`（后端自带语义；mem0 = search + scope 过滤） |
| `HYBRID_BUILTIN` | `MemoryRecallEngine` 三路加权 RRF（语义路读 memory_vectors） |
| `KEYWORD` | 引擎两路（关键词 + 时间）——即降级态的常态化 |

## 2. 行为不变量（参数化契约测试钉死，四档覆盖）

1. **写入即可见**：append 后 load/recall 立即反映（契约一，不缓存）。
2. **核心区永不截断、不参与检索、不入向量索引**（契约二/四范围；FR-005 仅归档条目向量化）。
3. **scope 显式**（契约三）；分区必选——装配期能力校验，无降维。
4. **per-Agent 隔离**：任一档下 A 的检索绝不命中 B 的条目（sqlite 档凭 agent_name 列）。
5. **大小写统一**：recallByKeyword 跨档不区分大小写（FR-002）。
6. **降级可读**：embedding 供给异常 ⇒ 语义路缺席、其余照常，结果尾行标注（仅已配置模式）。
7. **零丢失**：append 不因任何索引/向量化异常失败（FR-005）。
8. **确定性**：mock 向量下同输入恒同输出（SC-004/005）。
9. **未配置字节级兼容**：embedding 未配置时 recall 输出与旧实现一致（除大小写修正）——
   由 RecallBackwardCompatTest 对照参考实现断言（SC-002/003）。

## 3. mem0 档映射（FR-017，D8）

| 操作 | mem0 调用 | 关键点 |
|---|---|---|
| append(core) | `POST /v1/memories/ {infer:false, metadata:{scope:CORE}}` | 原文一字不差（契约二保真） |
| append(archival) | 同上 `{infer:true, metadata:{scope:ARCHIVAL}}` | 交 mem0 提炼/冲突消解 |
| load() | 两次 `get_all` + metadata 过滤 | core 全量 + archival 窗口 |
| recallByKeyword | `search {query, filters:{scope:ARCHIVAL}}` | 过滤有效性实测（issue #3773 版本核验，必要时回退 v1 参数式过滤） |
| 故障 | 连接/HTTP 异常 → 可读 IllegalStateException | 工具层转可读结果入审计，对话不中断；写失败如实呈现 |

鉴权：`Authorization: Bearer ${MEM0_API_KEY}`（配置 `memory.mem0.api-key` 环境变量引用；
OSS 无鉴权部署允许留空）。

## 4. recall_memory 工具行为（FR-001/009）

- 签名不变：`recall_memory(keyword)`，返回 String。
- 结果形态：命中条目原文按融合序逐行；无命中返回「没有找到相关记忆」（现状文案）。
- 降级标注：**仅已配置向量化且本次降级**时，结果末尾追加一行
  `（语义检索暂不可用，已按关键词与时间返回）`；未配置模式绝不追加（SC-002 字节级兼容）。
- 审计四要素落位：查询原文 = input_json；命中明细 = result 文本行；耗时 = duration_ms 列；
  降级标记 = 结果尾行（有则降级）。统一观测看板消费时按此解析；如需完全结构化 JSON，
  待知识/记忆统一看板特性一并处理（不在本期改工具返回形态）。
