package io.oryxos.core.flow;

import java.time.Instant;
import java.util.Objects;

/**
 * One timeline event for Flow run replay (047 / #469). Derived from persisted steps — no separate
 * event table.
 */
public record FlowTimelineEvent(
    Instant at,
    String runId,
    String stepId,
    String nodeId,
    FlowNodeType nodeType,
    FlowStepState state,
    String error,
    String detail) {

  public FlowTimelineEvent {
    at = Objects.requireNonNull(at, "at");
    runId = Objects.requireNonNull(runId, "runId").strip();
    stepId = stepId == null ? "" : stepId.strip();
    nodeId = nodeId == null ? "" : nodeId.strip();
    nodeType = Objects.requireNonNull(nodeType, "nodeType");
    state = Objects.requireNonNull(state, "state");
    error = error == null || error.isBlank() ? null : error.strip();
    detail = detail == null || detail.isBlank() ? null : detail.strip();
  }
}
