package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** flow_runs：046 / #468 持久化 Flow 运行。 */
@Entity
@Table(name = "flow_runs")
public class FlowRunEntity {

  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "flow_id", nullable = false)
  private String flowId;

  @Column(name = "flow_version", nullable = false, length = 64)
  private String flowVersion;

  @Column(name = "definition_markdown", nullable = false, columnDefinition = "TEXT")
  private String definitionMarkdown;

  @Column(nullable = false, length = 32)
  private String state;

  @Column(name = "entry_node_id", length = 128)
  private String entryNodeId;

  @Column(name = "current_node_id", length = 128)
  private String currentNodeId;

  @Column(name = "inputs_json", columnDefinition = "TEXT")
  private String inputsJson;

  @Column(name = "context_json", columnDefinition = "TEXT")
  private String contextJson;

  @Column(name = "last_error", length = 1024)
  private String lastError;

  @Column(nullable = false)
  private int attempt;

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

  public String getFlowId() {
    return flowId;
  }

  public void setFlowId(String flowId) {
    this.flowId = flowId;
  }

  public String getFlowVersion() {
    return flowVersion;
  }

  public void setFlowVersion(String flowVersion) {
    this.flowVersion = flowVersion;
  }

  public String getDefinitionMarkdown() {
    return definitionMarkdown;
  }

  public void setDefinitionMarkdown(String definitionMarkdown) {
    this.definitionMarkdown = definitionMarkdown;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getEntryNodeId() {
    return entryNodeId;
  }

  public void setEntryNodeId(String entryNodeId) {
    this.entryNodeId = entryNodeId;
  }

  public String getCurrentNodeId() {
    return currentNodeId;
  }

  public void setCurrentNodeId(String currentNodeId) {
    this.currentNodeId = currentNodeId;
  }

  public String getInputsJson() {
    return inputsJson;
  }

  public void setInputsJson(String inputsJson) {
    this.inputsJson = inputsJson;
  }

  public String getContextJson() {
    return contextJson;
  }

  public void setContextJson(String contextJson) {
    this.contextJson = contextJson;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
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
