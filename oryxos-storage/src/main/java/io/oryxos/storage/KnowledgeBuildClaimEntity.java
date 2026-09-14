package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** knowledge_build_claims：知识索引一次构建的执行权（027）——kb_name 唯一约束即互斥；表结构以迁移目录为唯一权威。 */
@Entity
@Table(name = "knowledge_build_claims")
public class KnowledgeBuildClaimEntity {

  @Id
  @Column(name = "kb_name")
  private String kbName;

  @Column(nullable = false)
  private String owner;

  @Column(name = "lease_until", nullable = false)
  private Instant leaseUntil;

  @Column(nullable = false)
  private long generation;

  public String getKbName() {
    return kbName;
  }

  public void setKbName(String kbName) {
    this.kbName = kbName;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public void setLeaseUntil(Instant leaseUntil) {
    this.leaseUntil = leaseUntil;
  }

  public long getGeneration() {
    return generation;
  }

  public void setGeneration(long generation) {
    this.generation = generation;
  }
}
