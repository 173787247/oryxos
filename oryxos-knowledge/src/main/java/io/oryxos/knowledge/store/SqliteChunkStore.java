package io.oryxos.knowledge.store;

import io.oryxos.core.embedding.VectorCodec;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.storage.KnowledgeChunkEntity;
import io.oryxos.storage.KnowledgeChunkRepository;
import io.oryxos.storage.KnowledgeDocumentEntity;
import io.oryxos.storage.KnowledgeDocumentRepository;
import java.util.List;
import java.util.Optional;

/** SQLite 实现（默认档，research D1）：向量以 float32[] 小端序 BLOB 落 knowledge_chunks。 */
public class SqliteChunkStore implements ChunkStore {

  private final KnowledgeDocumentRepository documents;
  private final KnowledgeChunkRepository chunks;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入的共享单例，构造注入存同一引用正是意图（镜像既有 SuppressFBWarnings 模式）。")
  public SqliteChunkStore(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks) {
    this.documents = documents;
    this.chunks = chunks;
  }

  @Override
  public DocumentRecord saveDocument(DocumentRecord document) {
    KnowledgeDocumentEntity entity =
        document.id() == null
            ? new KnowledgeDocumentEntity()
            : documents
                .findById(document.id())
                .orElseThrow(() -> new IllegalStateException("文档记录不存在: " + document.id()));
    entity.setKbName(document.kbName());
    entity.setRelPath(document.relPath());
    entity.setContentSha256(document.sha256());
    entity.setStatus(document.state().name());
    entity.setFailureReason(document.failureReason());
    entity.setChunkCount(document.chunkCount());
    entity.setGeneration(document.generation());
    entity.setIndexedAt(document.indexedAt());
    return toRecord(documents.save(entity));
  }

  @Override
  public Optional<DocumentRecord> findDocument(String kbName, String relPath, long generation) {
    return documents
        .findByKbNameAndRelPathAndGeneration(kbName, relPath, generation)
        .map(SqliteChunkStore::toRecord);
  }

  @Override
  public List<DocumentRecord> documents(String kbName, long generation) {
    return documents.findByKbNameAndGeneration(kbName, generation).stream()
        .map(SqliteChunkStore::toRecord)
        .toList();
  }

  @Override
  public List<DocumentRecord> allDocuments(String kbName) {
    return documents.findByKbName(kbName).stream().map(SqliteChunkStore::toRecord).toList();
  }

  @Override
  public void deleteDocument(long documentId) {
    chunks.deleteByDocumentId(documentId);
    documents.deleteById(documentId);
  }

  @Override
  public void saveChunks(List<ChunkRecord> records) {
    List<KnowledgeChunkEntity> entities = records.stream().map(SqliteChunkStore::toEntity).toList();
    chunks.saveAll(entities);
  }

  @Override
  public void deleteChunksOf(long documentId) {
    chunks.deleteByDocumentId(documentId);
  }

  @Override
  public List<ChunkRecord> chunks(String kbName, long generation) {
    return chunks.findByKbNameAndGeneration(kbName, generation).stream()
        .map(SqliteChunkStore::toRecord)
        .toList();
  }

  @Override
  public void deleteBase(String kbName) {
    chunks.deleteByKbName(kbName);
    documents.deleteByKbName(kbName);
  }

  @Override
  public void deleteGenerationsBelow(String kbName, long generation) {
    chunks.deleteByKbNameAndGenerationLessThan(kbName, generation);
    documents.deleteByKbNameAndGenerationLessThan(kbName, generation);
  }

  @Override
  public void deleteGeneration(String kbName, long generation) {
    chunks.deleteByKbNameAndGeneration(kbName, generation);
    documents.deleteByKbNameAndGeneration(kbName, generation);
  }

  private static DocumentRecord toRecord(KnowledgeDocumentEntity entity) {
    return new DocumentRecord(
        entity.getId(),
        entity.getKbName(),
        entity.getRelPath(),
        entity.getContentSha256(),
        DocumentState.valueOf(entity.getStatus()),
        entity.getFailureReason(),
        entity.getChunkCount(),
        entity.getGeneration(),
        entity.getIndexedAt());
  }

  private static ChunkRecord toRecord(KnowledgeChunkEntity entity) {
    return new ChunkRecord(
        entity.getId(),
        entity.getDocumentId(),
        entity.getKbName(),
        entity.getSeq(),
        entity.getPageNo(),
        entity.getContent(),
        decode(entity.getEmbedding()),
        entity.getEmbeddingModel(),
        entity.getGeneration());
  }

  private static KnowledgeChunkEntity toEntity(ChunkRecord record) {
    KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
    entity.setDocumentId(record.documentId());
    entity.setKbName(record.kbName());
    entity.setSeq(record.seq());
    entity.setPageNo(record.pageNo());
    entity.setContent(record.content());
    entity.setEmbedding(encode(record.embedding()));
    entity.setDim(record.embedding() == null ? null : record.embedding().length);
    entity.setEmbeddingModel(record.embeddingModel());
    entity.setGeneration(record.generation());
    return entity;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
      justification = "null 表示「无向量」（降级期片段），与零长向量语义不同，不得混淆。")
  static byte[] encode(float[] vector) {
    if (vector == null) {
      return null;
    }
    return VectorCodec.encode(vector);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
      justification = "null 表示「无向量」（降级期片段），与零长向量语义不同，不得混淆。")
  static float[] decode(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    return VectorCodec.decode(bytes);
  }
}
