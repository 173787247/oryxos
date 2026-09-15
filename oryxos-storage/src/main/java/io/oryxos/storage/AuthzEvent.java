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
 * 授权拒绝审计（039）：只落拒绝行，放行不写。表结构以 db/migration V9 为唯一权威。
 *
 * <p>无更新/删除路径——追加型审计；写失败不得把拒绝变成放行（见 {@link AuthzEventRecorder}）。
 */
@Entity
@Table(name = "authz_events")
public class AuthzEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "principal_kind", nullable = false, length = 16)
  private String principalKind;

  @Column(name = "principal_id", nullable = false, length = 128)
  private String principalId;

  @Column(nullable = false, length = 32)
  private String action;

  @Column(name = "resource_type", length = 32)
  private String resourceType;

  @Column(name = "resource_id", length = 255)
  private String resourceId;

  @Column(nullable = false, length = 512)
  private String reason;

  @Column(name = "request_method", length = 16)
  private String requestMethod;

  @Column(name = "request_path", length = 512)
  private String requestPath;

  @Column(name = "trace_id", length = 64)
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

  public String getPrincipalKind() {
    return principalKind;
  }

  public void setPrincipalKind(String principalKind) {
    this.principalKind = principalKind;
  }

  public String getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(String principalId) {
    this.principalId = principalId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public void setResourceId(String resourceId) {
    this.resourceId = resourceId;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getRequestMethod() {
    return requestMethod;
  }

  public void setRequestMethod(String requestMethod) {
    this.requestMethod = requestMethod;
  }

  public String getRequestPath() {
    return requestPath;
  }

  public void setRequestPath(String requestPath) {
    this.requestPath = requestPath;
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
}
