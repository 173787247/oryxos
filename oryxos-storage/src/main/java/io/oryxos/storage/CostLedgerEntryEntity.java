package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "cost_ledger_entries")
public class CostLedgerEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id")
  private String runId;

  @Column(name = "task_id")
  private String taskId;

  @Column(name = "agent_name")
  private String agentName;

  @Column(name = "team_id")
  private String teamId;

  private String provider;
  private String model;

  @Column(name = "source_kind", nullable = false)
  private String sourceKind;

  @Column(name = "source_ref")
  private String sourceRef;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

  @Column(name = "llm_cost_micros", nullable = false)
  private long llmCostMicros;

  @Column(name = "tool_cost_micros", nullable = false)
  private long toolCostMicros;

  @Column(name = "latency_ms", nullable = false)
  private long latencyMs;

  @Column(name = "price_version")
  private Long priceVersion;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "trace_id")
  private String traceId;

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

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  public String getAgentName() {
    return agentName;
  }

  public void setAgentName(String agentName) {
    this.agentName = agentName;
  }

  public String getTeamId() {
    return teamId;
  }

  public void setTeamId(String teamId) {
    this.teamId = teamId;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getSourceKind() {
    return sourceKind;
  }

  public void setSourceKind(String sourceKind) {
    this.sourceKind = sourceKind;
  }

  public String getSourceRef() {
    return sourceRef;
  }

  public void setSourceRef(String sourceRef) {
    this.sourceRef = sourceRef;
  }

  public Integer getPromptTokens() {
    return promptTokens;
  }

  public void setPromptTokens(Integer promptTokens) {
    this.promptTokens = promptTokens;
  }

  public Integer getCompletionTokens() {
    return completionTokens;
  }

  public void setCompletionTokens(Integer completionTokens) {
    this.completionTokens = completionTokens;
  }

  public Integer getTotalTokens() {
    return totalTokens;
  }

  public void setTotalTokens(Integer totalTokens) {
    this.totalTokens = totalTokens;
  }

  public long getLlmCostMicros() {
    return llmCostMicros;
  }

  public void setLlmCostMicros(long llmCostMicros) {
    this.llmCostMicros = llmCostMicros;
  }

  public long getToolCostMicros() {
    return toolCostMicros;
  }

  public void setToolCostMicros(long toolCostMicros) {
    this.toolCostMicros = toolCostMicros;
  }

  public long getLatencyMs() {
    return latencyMs;
  }

  public void setLatencyMs(long latencyMs) {
    this.latencyMs = latencyMs;
  }

  public Long getPriceVersion() {
    return priceVersion;
  }

  public void setPriceVersion(Long priceVersion) {
    this.priceVersion = priceVersion;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
