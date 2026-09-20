package io.oryxos.core.durable;

/**
 * 耐久任务状态机（043 / #465 / epic #455）。
 *
 * <p>终态：{@link #SUCCEEDED} / {@link #FAILED} / {@link #CANCELLED}。{@link #WAITING_APPROVAL}
 * 为可恢复挂起态，进程重启后不得被 reconcile 成失败。
 */
public enum DurableTaskState {
  QUEUED,
  RUNNING,
  WAITING_APPROVAL,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }

  public boolean waitingApproval() {
    return this == WAITING_APPROVAL;
  }
}
