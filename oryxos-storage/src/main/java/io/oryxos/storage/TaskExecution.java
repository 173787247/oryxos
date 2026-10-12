package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** task_executions：定时任务每次执行的历史（28 节，成功失败都记）——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "task_executions")
public class TaskExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "schedule_id")
  private String scheduleId;

  @Column(name = "legacy_task_key")
  private String legacyTaskKey;

  @Column(name = "legacy_migrated", nullable = false)
  private boolean legacyMigrated;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  /**
   * 本次执行的**到点时刻**（调度时算出的理论触发值，也是到点认领的 CAS 值）；手动/补跑为 null。
   *
   * <p>与 {@link #startedAt} 的区别是实质性的：{@code startedAt} 是执行开始时的墙钟，在认领之后才取，
   * 可能比到点晚若干毫秒到数秒。判「同一到点是否被执行两次」必须用本列 —— 按 {@code startedAt} 分桶会把「两次相邻到点各自被拖慢」误报成双发。
   */
  @Column(name = "fire_time")
  private Instant fireTime;

  @Column(nullable = false)
  private boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  public Long getId() {
    return id;
  }

  public String getScheduleId() {
    return scheduleId;
  }

  public void setScheduleId(String scheduleId) {
    this.scheduleId = scheduleId;
  }

  public String getLegacyTaskKey() {
    return legacyTaskKey;
  }

  public void setLegacyTaskKey(String legacyTaskKey) {
    this.legacyTaskKey = legacyTaskKey;
  }

  public boolean isLegacyMigrated() {
    return legacyMigrated;
  }

  public void setLegacyMigrated(boolean legacyMigrated) {
    this.legacyMigrated = legacyMigrated;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFireTime() {
    return fireTime;
  }

  public void setFireTime(Instant fireTime) {
    this.fireTime = fireTime;
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
}
