package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** knowledge_generations：检索唯一可见的已提交代次（027）——每库一行；表结构以迁移目录为唯一权威。 */
@Entity
@Table(name = "knowledge_generations")
public class KnowledgeGenerationEntity {

  @Id
  @Column(name = "kb_name")
  private String kbName;

  @Column(name = "committed_generation", nullable = false)
  private long committedGeneration;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public String getKbName() {
    return kbName;
  }

  public void setKbName(String kbName) {
    this.kbName = kbName;
  }

  public long getCommittedGeneration() {
    return committedGeneration;
  }

  public void setCommittedGeneration(long committedGeneration) {
    this.committedGeneration = committedGeneration;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
