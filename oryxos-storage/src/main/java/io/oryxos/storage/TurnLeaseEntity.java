package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** session_turn_leases：轮次互斥载体（026）——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "session_turn_leases")
public class TurnLeaseEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(nullable = false)
  private String owner;

  @Column(name = "lease_until", nullable = false)
  private Instant leaseUntil;

  @Column(name = "acquired_at", nullable = false)
  private Instant acquiredAt;

  /** 悬空轮失败留痕：本轮关联的 agent_executions id（无 execution 的路径为空）。 */
  @Column(name = "agent_execution_id")
  private Long agentExecutionId;

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
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

  public Instant getAcquiredAt() {
    return acquiredAt;
  }

  public void setAcquiredAt(Instant acquiredAt) {
    this.acquiredAt = acquiredAt;
  }

  public Long getAgentExecutionId() {
    return agentExecutionId;
  }

  public void setAgentExecutionId(Long agentExecutionId) {
    this.agentExecutionId = agentExecutionId;
  }
}
