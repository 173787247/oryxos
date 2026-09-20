package io.oryxos.core.flow;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted Flow run snapshot (046 / #468). {@code contextJson} holds accumulated node outputs
 * keyed as {@code nodeId.port}.
 */
public record FlowRun(
    String id,
    String flowId,
    String flowVersion,
    String definitionMarkdown,
    FlowRunState state,
    String entryNodeId,
    String currentNodeId,
    String inputsJson,
    String contextJson,
    String lastError,
    int attempt,
    Instant createdAt,
    Instant updatedAt) {

  public FlowRun {
    id = Objects.requireNonNull(id, "id").strip();
    flowId = flowId == null ? "" : flowId.strip();
    flowVersion = flowVersion == null ? "0" : flowVersion.strip();
    definitionMarkdown = definitionMarkdown == null ? "" : definitionMarkdown;
    state = Objects.requireNonNull(state, "state");
    entryNodeId = entryNodeId == null ? "" : entryNodeId.strip();
    currentNodeId = currentNodeId == null || currentNodeId.isBlank() ? null : currentNodeId.strip();
    inputsJson = inputsJson == null ? "{}" : inputsJson;
    contextJson = contextJson == null ? "{}" : contextJson;
    lastError = lastError == null || lastError.isBlank() ? null : lastError.strip();
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public FlowRun withState(FlowRunState next, Instant at) {
    return withState(next, at, lastError);
  }

  public FlowRun withState(FlowRunState next, Instant at, String error) {
    return new FlowRun(
        id,
        flowId,
        flowVersion,
        definitionMarkdown,
        next,
        entryNodeId,
        currentNodeId,
        inputsJson,
        contextJson,
        error,
        attempt,
        createdAt,
        at);
  }

  public FlowRun withCurrentNode(String nodeId, Instant at) {
    return new FlowRun(
        id,
        flowId,
        flowVersion,
        definitionMarkdown,
        state,
        entryNodeId,
        nodeId,
        inputsJson,
        contextJson,
        lastError,
        attempt,
        createdAt,
        at);
  }

  public FlowRun withContext(String nextContextJson, Instant at) {
    return new FlowRun(
        id,
        flowId,
        flowVersion,
        definitionMarkdown,
        state,
        entryNodeId,
        currentNodeId,
        inputsJson,
        nextContextJson,
        lastError,
        attempt,
        createdAt,
        at);
  }

  public FlowRun withAttempt(int nextAttempt, Instant at) {
    return new FlowRun(
        id,
        flowId,
        flowVersion,
        definitionMarkdown,
        state,
        entryNodeId,
        currentNodeId,
        inputsJson,
        contextJson,
        lastError,
        nextAttempt,
        createdAt,
        at);
  }
}
