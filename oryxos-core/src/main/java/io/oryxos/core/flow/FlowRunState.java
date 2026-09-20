package io.oryxos.core.flow;

/**
 * Durable Flow run state machine (046 / #468).
 *
 * <p>Terminal: {@link #SUCCEEDED} / {@link #FAILED} / {@link #CANCELLED}. {@link #WAITING} is
 * recoverable after process restart (human/approval hook; full HITL UX is #469).
 */
public enum FlowRunState {
  QUEUED,
  RUNNING,
  WAITING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }

  public boolean waiting() {
    return this == WAITING;
  }
}
