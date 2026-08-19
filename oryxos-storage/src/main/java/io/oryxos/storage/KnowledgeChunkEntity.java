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
 * knowledge_chunks 行（014 知识库）——embedding 为 float32[] BLOB（小端序编码由上层负责）； dim + embedding_model
 * 支撑一致性校验（FR-014）。表结构以手工 schema.sql 为唯一权威。
 */
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunkEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "document_id", nullable = false)
  private long documentId;

  @Column(name = "kb_name", nullable = false)
  private String kbName;

  @Column(nullable = false)
  private int seq;

  @Column(name = "page_no")
  private Integer pageNo;

  @Column(nullable = false)
  private String content;

  @Column private byte[] embedding;

  @Column private Integer dim;

  @Column(name = "embedding_model")
  private String embeddingModel;

  @Column(nullable = false)
  private long generation;

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

  public long getDocumentId() {
    return documentId;
  }

  public void setDocumentId(long documentId) {
    this.documentId = documentId;
  }

  public String getKbName() {
    return kbName;
  }

  public void setKbName(String kbName) {
    this.kbName = kbName;
  }

  public int getSeq() {
    return seq;
  }

  public void setSeq(int seq) {
    this.seq = seq;
  }

  public Integer getPageNo() {
    return pageNo;
  }

  public void setPageNo(Integer pageNo) {
    this.pageNo = pageNo;
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

  public Integer getDim() {
    return dim;
  }

  public void setDim(Integer dim) {
    this.dim = dim;
  }

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public void setEmbeddingModel(String embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  public long getGeneration() {
    return generation;
  }

  public void setGeneration(long generation) {
    this.generation = generation;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
