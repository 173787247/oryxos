package io.oryxos.knowledge.store;

import io.oryxos.core.knowledge.model.DocumentState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 片段索引存储端口——检索基建的可插拔位（配置键 {@code knowledge.store}，默认 sqlite，research D1）： v0.4 换 pgvector
 * 只替换实现。接口形状不带业务耦合（FR-016），记忆语义化升级可复用。
 */
public interface ChunkStore {

  /** 文档索引记录（派生数据，可从文件系统重建）。 */
  record DocumentRecord(
      Long id,
      String kbName,
      String relPath,
      String sha256,
      DocumentState state,
      String failureReason,
      int chunkCount,
      long generation,
      Instant indexedAt) {

    public DocumentRecord withState(DocumentState newState, String reason) {
      return new DocumentRecord(
          id, kbName, relPath, sha256, newState, reason, chunkCount, generation, indexedAt);
    }

    public DocumentRecord ready(int chunks, Instant at) {
      return new DocumentRecord(
          id, kbName, relPath, sha256, DocumentState.READY, null, chunks, generation, at);
    }
  }

  /** 片段记录；embedding 可空（降级期无向量）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "向量数组仅在检索引擎内部流转且约定只读；万级片段热路径上防御性拷贝代价不值。")
  record ChunkRecord(
      Long id,
      long documentId,
      String kbName,
      int seq,
      Integer pageNo,
      String content,
      float[] embedding,
      String embeddingModel,
      long generation) {}

  /** 插入或按 id 更新；返回带 id 的记录。 */
  DocumentRecord saveDocument(DocumentRecord document);

  Optional<DocumentRecord> findDocument(String kbName, String relPath, long generation);

  List<DocumentRecord> documents(String kbName, long generation);

  /** 全部代的文档（对账/推断活跃代用）。 */
  List<DocumentRecord> allDocuments(String kbName);

  /** 删除一份文档及其全部片段。 */
  void deleteDocument(long documentId);

  void saveChunks(List<ChunkRecord> chunks);

  void deleteChunksOf(long documentId);

  List<ChunkRecord> chunks(String kbName, long generation);

  /** 删除整库全部行。 */
  void deleteBase(String kbName);

  /** 双缓冲切换后清理旧代（FR-024）。 */
  void deleteGenerationsBelow(String kbName, long generation);

  /** 重建失败时丢弃未完成的新代，旧代不受影响。 */
  void deleteGeneration(String kbName, long generation);
}
