package io.oryxos.knowledge.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 引擎单测用的内存实现：与 SqliteChunkStore 同语义，免 Spring/SQLite 起箱。 */
public final class InMemoryChunkStore implements ChunkStore {

  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, DocumentRecord> documents = new ConcurrentHashMap<>();
  private final Map<Long, ChunkRecord> chunks = new ConcurrentHashMap<>();

  @Override
  public synchronized DocumentRecord saveDocument(DocumentRecord document) {
    long id = document.id() == null ? ids.incrementAndGet() : document.id();
    DocumentRecord saved =
        new DocumentRecord(
            id,
            document.kbName(),
            document.relPath(),
            document.sha256(),
            document.state(),
            document.failureReason(),
            document.chunkCount(),
            document.generation(),
            document.indexedAt());
    documents.put(id, saved);
    return saved;
  }

  @Override
  public Optional<DocumentRecord> findDocument(String kbName, String relPath, long generation) {
    return documents.values().stream()
        .filter(
            doc ->
                doc.kbName().equals(kbName)
                    && doc.relPath().equals(relPath)
                    && doc.generation() == generation)
        .findFirst();
  }

  @Override
  public List<DocumentRecord> documents(String kbName, long generation) {
    return documents.values().stream()
        .filter(doc -> doc.kbName().equals(kbName) && doc.generation() == generation)
        .sorted(Comparator.comparing(DocumentRecord::relPath))
        .toList();
  }

  @Override
  public List<DocumentRecord> allDocuments(String kbName) {
    return documents.values().stream().filter(doc -> doc.kbName().equals(kbName)).toList();
  }

  @Override
  public synchronized void deleteDocument(long documentId) {
    documents.remove(documentId);
    chunks.values().removeIf(chunk -> chunk.documentId() == documentId);
  }

  @Override
  public synchronized void saveChunks(List<ChunkRecord> records) {
    for (ChunkRecord record : records) {
      long id = record.id() == null ? ids.incrementAndGet() : record.id();
      chunks.put(
          id,
          new ChunkRecord(
              id,
              record.documentId(),
              record.kbName(),
              record.seq(),
              record.pageNo(),
              record.content(),
              record.embedding(),
              record.embeddingModel(),
              record.generation()));
    }
  }

  @Override
  public synchronized void deleteChunksOf(long documentId) {
    chunks.values().removeIf(chunk -> chunk.documentId() == documentId);
  }

  @Override
  public List<ChunkRecord> chunks(String kbName, long generation) {
    return new ArrayList<>(
        chunks.values().stream()
            .filter(chunk -> chunk.kbName().equals(kbName) && chunk.generation() == generation)
            .sorted(Comparator.comparingLong(ChunkRecord::id))
            .toList());
  }

  @Override
  public synchronized void deleteBase(String kbName) {
    documents.values().removeIf(doc -> doc.kbName().equals(kbName));
    chunks.values().removeIf(chunk -> chunk.kbName().equals(kbName));
  }

  @Override
  public synchronized void deleteGenerationsBelow(String kbName, long generation) {
    documents
        .values()
        .removeIf(doc -> doc.kbName().equals(kbName) && doc.generation() < generation);
    chunks
        .values()
        .removeIf(chunk -> chunk.kbName().equals(kbName) && chunk.generation() < generation);
  }

  @Override
  public synchronized void deleteGeneration(String kbName, long generation) {
    documents
        .values()
        .removeIf(doc -> doc.kbName().equals(kbName) && doc.generation() == generation);
    chunks
        .values()
        .removeIf(chunk -> chunk.kbName().equals(kbName) && chunk.generation() == generation);
  }

  /** 供断言用：记录 Instant 以避免测试里空转。 */
  public Instant now() {
    return Instant.now();
  }
}
