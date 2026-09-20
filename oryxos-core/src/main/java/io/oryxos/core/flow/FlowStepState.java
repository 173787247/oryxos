package io.oryxos.core.flow;

/** Per-node step status within a Flow run (046 / #468 + CANCELLED for #469). */
public enum FlowStepState {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED,
  WAITING,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
  }

  public boolean succeeded() {
    return this == SUCCEEDED;
  }
}
