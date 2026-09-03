package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** tool_invocations 审计记录——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tool_name", nullable = false)
  private String toolName;

  @Column(name = "input_json")
  private String inputJson;

  @Column(name = "result_json")
  private String resultJson;

  @Column(name = "profile_name")
  private String profileName;

  /** 单轮处理串联标识（021）：同一次消息处理的全部审计记录共享；升级前旧行为 null。 */
  @Column(name = "trace_id")
  private String traceId;

  @Column(nullable = false)
  private boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  /** 拦截来源标记（020）：'policy' = 工具策略拒绝；未被拦截为 null。 */
  @Column(name = "blocked_by")
  private String blockedBy;

  /** 执行后端标识（024）：'local' / 'docker'；历史行为 null（≡ local，查询层兼容，D4）。 */
  @Column(name = "execution_backend")
  private String executionBackend;

  /** docker 档容器 ID（024，容器执行溯源）；local 档与历史行为为 null。 */
  @Column(name = "container_id")
  private String containerId;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

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

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }

  public String getInputJson() {
    return inputJson;
  }

  public void setInputJson(String inputJson) {
    this.inputJson = inputJson;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
  }

  public String getProfileName() {
    return profileName;
  }

  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getBlockedBy() {
    return blockedBy;
  }

  public void setBlockedBy(String blockedBy) {
    this.blockedBy = blockedBy;
  }

  public String getExecutionBackend() {
    return executionBackend;
  }

  public void setExecutionBackend(String executionBackend) {
    this.executionBackend = executionBackend;
  }

  public String getContainerId() {
    return containerId;
  }

  public void setContainerId(String containerId) {
    this.containerId = containerId;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }
}
