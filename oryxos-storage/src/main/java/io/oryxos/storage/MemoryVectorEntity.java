package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * memory_vectors 行（015 记忆检索升级）——归档记忆条目的向量索引，派生数据、可从记忆本体全量重建。 embedding 为 float32[] 小端序
 * BLOB（编解码由上层复用 014 实现）；entry_hash = sha256(agent|scope|条目原文) 跨后端档统一寻址；仅 ARCHIVAL
 * 条目产生行（FR-005）。表结构以 db/migration 迁移目录为唯一权威。
 */
@Entity
@Table(name = "memory_vectors")
public class MemoryVectorEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "entry_hash", nullable = false)
  private String entryHash;

  @Column(name = "agent_name", nullable = false)
  private String agentName;

  @Column(nullable = false)
  private String content;

  @Column(nullable = false)
  private byte[] embedding;

  @Column(nullable = false)
  private int dim;

  @Column(name = "embedding_model", nullable = false)
  private String embeddingModel;

  @Column(name = "entry_time")
  private Instant entryTime;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public String getEntryHash() {
    return entryHash;
  }

  public void setEntryHash(String entryHash) {
    this.entryHash = entryHash;
  }

  public String getAgentName() {
    return agentName;
  }

  public void setAgentName(String agentName) {
    this.agentName = agentName;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public byte[] getEmbedding() {
    return embedding == null ? null : embedding.clone();
  }

  public void setEmbedding(byte[] embedding) {
    this.embedding = embedding == null ? null : embedding.clone();
  }

  public int getDim() {
    return dim;
  }

  public void setDim(int dim) {
    this.dim = dim;
  }

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public void setEmbeddingModel(String embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  public Instant getEntryTime() {
    return entryTime;
  }

  public void setEntryTime(Instant entryTime) {
    this.entryTime = entryTime;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
