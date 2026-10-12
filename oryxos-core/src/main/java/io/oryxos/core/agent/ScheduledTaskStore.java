package io.oryxos.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * Persistent runtime state and execution history for schedules defined by Agent profiles.
 *
 * <p>Configuration belongs to AGENT.md. This store owns the stable runtime {@code scheduleId}
 * generated for each profile/key pair.
 */
public interface ScheduledTaskStore {

  /**
   * Reconcile the configuration with stored state and return its stable, globally unique schedule
   * ID. A matching profile/key pair retains its enabled flag and execution history.
   */
  String reconcile(
      String profileName,
      String key,
      String name,
      String cron,
      String zone,
      String message,
      Instant nextRunAt);

  /**
   * Record an execution and update the schedule's runtime state.
   *
   * <p>{@code fireTime} 是本次执行的到点时刻（理论触发值）；手动/补跑没有到点，传 {@code null}。 它与 {@code
   * startedAt}（执行开始时的墙钟）不同 —— 判「同一到点是否被执行两次」要用它。
   */
  void recordExecution(
      String scheduleId,
      String sessionId,
      Instant startedAt,
      Instant fireTime,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt);

  /** Return the enabled state for an existing schedule ID. */
  boolean isEnabled(String scheduleId);

  /** Enable or disable an existing schedule ID. */
  void setEnabled(String scheduleId, boolean enabled);

  /** Retire a configuration definition while retaining its runtime state and history. */
  void retire(String profileName, String key);

  /** List currently configured, non-retired schedules. */
  List<ScheduledTaskView> list();

  /** Find all profile schedules sharing the supplied configuration key. */
  List<ScheduledTaskView> findByKey(String key);

  /** Whether a persisted schedule exists, including a retired one retained for history. */
  boolean exists(String scheduleId);

  /** Return the most recent execution history for a schedule ID, including a retired schedule. */
  List<TaskExecutionView> executions(String scheduleId, int limit);
}
