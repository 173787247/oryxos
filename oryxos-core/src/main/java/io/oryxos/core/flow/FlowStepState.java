package io.oryxos.core.flow;

/** Per-node step status within a Flow run (046 / #468). */
public enum FlowStepState {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED,
  WAITING;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == SKIPPED;
  }

  public boolean succeeded() {
    return this == SUCCEEDED;
  }
}
