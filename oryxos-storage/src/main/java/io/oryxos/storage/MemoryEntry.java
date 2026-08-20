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
 * 长期记忆条目（SqliteMemoryStore 后端，22 节）——表结构以手工 schema.sql 为唯一权威。 agent_name（015 FR-014）：记忆跟 Agent 走；无
 * Agent 上下文时归 '__global__'（与 markdown 档全局回退语义对齐）。
 */
@Entity
@Table(name = "memory_entries")
public class MemoryEntry {

  /** 无 Agent 上下文时的占位作用域（存量行升级也归此值）。 */
  public static final String GLOBAL_AGENT = "__global__";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "agent_name", nullable = false)
  private String agentName;

  @Column(nullable = false)
  private String scope; // CORE / ARCHIVAL

  @Column(nullable = false)
  private String content;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (agentName == null) {
      agentName = GLOBAL_AGENT;
    }
  }

  public Long getId() {
    return id;
  }

  public String getAgentName() {
    return agentName;
  }

  public void setAgentName(String agentName) {
    this.agentName = agentName;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
