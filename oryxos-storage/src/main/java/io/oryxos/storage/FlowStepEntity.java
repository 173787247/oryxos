package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** flow_steps：046 / #468 节点级执行记录。 */
@Entity
@Table(name = "flow_steps")
public class FlowStepEntity {

  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "run_id", nullable = false, length = 64)
  private String runId;

  @Column(name = "node_id", nullable = false, length = 128)
  private String nodeId;

  @Column(name = "node_type", nullable = false, length = 32)
  private String nodeType;

  @Column(nullable = false, length = 32)
  private String state;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "idempotency_key", nullable = false, length = 255, unique = true)
  private String idempotencyKey;

  @Column(name = "inputs_json", columnDefinition = "TEXT")
  private String inputsJson;

  @Column(name = "outputs_json", columnDefinition = "TEXT")
  private String outputsJson;

  @Column(length = 1024)
  private String error;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

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

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public void setNodeId(String nodeId) {
    this.nodeId = nodeId;
  }

  public String getNodeType() {
    return nodeType;
  }

  public void setNodeType(String nodeType) {
    this.nodeType = nodeType;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getInputsJson() {
    return inputsJson;
  }

  public void setInputsJson(String inputsJson) {
    this.inputsJson = inputsJson;
  }

  public String getOutputsJson() {
    return outputsJson;
  }

  public void setOutputsJson(String outputsJson) {
    this.outputsJson = outputsJson;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
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
