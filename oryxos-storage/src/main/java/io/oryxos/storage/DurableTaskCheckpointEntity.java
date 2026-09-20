package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** durable_task_checkpoints：043 / #465 耐久任务检查点。 */
@Entity
@Table(name = "durable_task_checkpoints")
public class DurableTaskCheckpointEntity {

  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "execution_id")
  private Long executionId;

  @Column(name = "session_id", length = 128)
  private String sessionId;

  @Column(name = "agent_name", nullable = false)
  private String agentName;

  @Column(nullable = false, length = 32)
  private String state;

  @Column(name = "idempotency_key", nullable = false, length = 255, unique = true)
  private String idempotencyKey;

  @Column(name = "checkpoint_kind", nullable = false, length = 64)
  private String checkpointKind;

  @Column(name = "tool_name")
  private String toolName;

  @Column(name = "tool_call_id", length = 128)
  private String toolCallId;

  @Column(name = "arguments_json", columnDefinition = "TEXT")
  private String argumentsJson;

  @Column(name = "policy_version", length = 64)
  private String policyVersion;

  @Column(name = "rule_id", length = 128)
  private String ruleId;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "last_error", length = 1024)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Long getExecutionId() {
    return executionId;
  }

  public void setExecutionId(Long executionId) {
    this.executionId = executionId;
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

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getCheckpointKind() {
    return checkpointKind;
  }

  public void setCheckpointKind(String checkpointKind) {
    this.checkpointKind = checkpointKind;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  public void setToolCallId(String toolCallId) {
    this.toolCallId = toolCallId;
  }

  public String getArgumentsJson() {
    return argumentsJson;
  }

  public void setArgumentsJson(String argumentsJson) {
    this.argumentsJson = argumentsJson;
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

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
