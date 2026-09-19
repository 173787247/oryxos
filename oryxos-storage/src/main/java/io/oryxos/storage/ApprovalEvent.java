package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 审批审计事件（042 / #464）。表结构以 db/migration V18 为唯一权威。追加型——无更新/删除业务路径。 */
@Entity
@Table(name = "approval_events")
public class ApprovalEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "kind", nullable = false, length = 32)
  private String kind;

  @Column(name = "session_id", length = 128)
  private String sessionId;

  @Column(name = "agent_name", length = 255)
  private String agentName;

  @Column(name = "tool_name", length = 255)
  private String toolName;

  @Column(name = "action_type", length = 64)
  private String actionType;

  @Column(name = "policy_version", length = 64)
  private String policyVersion;

  @Column(name = "rule_id", length = 128)
  private String ruleId;

  @Column(name = "actor", nullable = false, length = 128)
  private String actor;

  @Column(name = "reason", length = 1024)
  private String reason;

  @Column(name = "ttl_seconds")
  private Integer ttlSeconds;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getAgentName() {
    return agentName;
  }

  public void setAgentName(String agentName) {
    this.agentName = agentName;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }

  public String getActionType() {
    return actionType;
  }

  public void setActionType(String actionType) {
    this.actionType = actionType;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public void setPolicyVersion(String policyVersion) {
    this.policyVersion = policyVersion;
  }

  public String getRuleId() {
    return ruleId;
  }

  public void setRuleId(String ruleId) {
    this.ruleId = ruleId;
  }

  public String getActor() {
    return actor;
  }

  public void setActor(String actor) {
    this.actor = actor;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public Integer getTtlSeconds() {
    return ttlSeconds;
  }

  public void setTtlSeconds(Integer ttlSeconds) {
    this.ttlSeconds = ttlSeconds;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
