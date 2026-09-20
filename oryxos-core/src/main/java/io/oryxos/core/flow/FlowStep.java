package io.oryxos.core.flow;

import java.time.Instant;
import java.util.Objects;

/** Persisted per-node step within a Flow run (046 / #468). */
public record FlowStep(
    String id,
    String runId,
    String nodeId,
    FlowNodeType nodeType,
    FlowStepState state,
    int attempt,
    String idempotencyKey,
    String inputsJson,
    String outputsJson,
    String error,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt) {

  public FlowStep {
    id = Objects.requireNonNull(id, "id").strip();
    runId = Objects.requireNonNull(runId, "runId").strip();
    nodeId = Objects.requireNonNull(nodeId, "nodeId").strip();
    nodeType = Objects.requireNonNull(nodeType, "nodeType");
    state = Objects.requireNonNull(state, "state");
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
    inputsJson = inputsJson == null ? "{}" : inputsJson;
    outputsJson = outputsJson == null ? "{}" : outputsJson;
    error = error == null || error.isBlank() ? null : error.strip();
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static String idempotencyKeyFor(String runId, String nodeId) {
    return "flow:" + runId + ":" + nodeId;
  }

  public FlowStep withState(FlowStepState next, Instant at) {
    return withState(next, at, error, outputsJson);
  }

  public FlowStep withState(FlowStepState next, Instant at, String nextError, String nextOutputs) {
    Instant finished =
        next.terminal() || next == FlowStepState.WAITING
            ? (finishedAt == null ? at : finishedAt)
            : finishedAt;
    if (next == FlowStepState.RUNNING) {
      finished = null;
    }
    if (next.succeeded() || next == FlowStepState.FAILED || next == FlowStepState.SKIPPED) {
      finished = at;
    }
    return new FlowStep(
        id,
        runId,
        nodeId,
        nodeType,
        next,
        attempt,
        idempotencyKey,
        inputsJson,
        nextOutputs == null ? outputsJson : nextOutputs,
        nextError,
        startedAt == null && next == FlowStepState.RUNNING ? at : startedAt,
        finished,
        createdAt,
        at);
  }

  public FlowStep withAttempt(int nextAttempt, Instant at) {
    return new FlowStep(
        id,
        runId,
        nodeId,
        nodeType,
        state,
        nextAttempt,
        idempotencyKey,
        inputsJson,
        outputsJson,
        error,
        startedAt,
        finishedAt,
        createdAt,
        at);
  }

  public FlowStep withInputs(String nextInputs, Instant at) {
    return new FlowStep(
        id,
        runId,
        nodeId,
        nodeType,
        state,
        attempt,
        idempotencyKey,
        nextInputs,
        outputsJson,
        error,
        startedAt,
        finishedAt,
        createdAt,
        at);
  }
}
