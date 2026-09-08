package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** knowledge_documents 行（014 知识库）——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocumentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "kb_name", nullable = false)
  private String kbName;

  @Column(name = "rel_path", nullable = false)
  private String relPath;

  @Column(name = "content_sha256", nullable = false)
  private String contentSha256;

  /** 状态机文本值：PENDING / INDEXING / READY / FAILED。 */
  @Column(nullable = false)
  private String status;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount;

  @Column(nullable = false)
  private long generation;

  @Column(name = "indexed_at")
  private Instant indexedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getKbName() {
    return kbName;
  }

  public void setKbName(String kbName) {
    this.kbName = kbName;
  }

  public String getRelPath() {
    return relPath;
  }

  public void setRelPath(String relPath) {
    this.relPath = relPath;
  }

  public String getContentSha256() {
    return contentSha256;
  }

  public void setContentSha256(String contentSha256) {
    this.contentSha256 = contentSha256;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public void setChunkCount(int chunkCount) {
    this.chunkCount = chunkCount;
  }

  public long getGeneration() {
    return generation;
  }

  public void setGeneration(long generation) {
    this.generation = generation;
  }

  public Instant getIndexedAt() {
    return indexedAt;
  }

  public void setIndexedAt(Instant indexedAt) {
    this.indexedAt = indexedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
