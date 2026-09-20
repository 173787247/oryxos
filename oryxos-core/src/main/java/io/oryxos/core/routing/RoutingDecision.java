package io.oryxos.core.routing;

import java.time.Instant;
import java.util.List;

/** Immutable explainable routing / fallback decision (#477). */
public record RoutingDecision(
    String id,
    String runId,
    String agentName,
    String selectedProvider,
    String selectedModel,
    List<RoutingReason> reasons,
    List<CandidateDisposition> candidates,
    List<RoutingCandidate> attemptOrder,
    Instant createdAt) {

  public RoutingDecision {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    attemptOrder = attemptOrder == null ? List.of() : List.copyOf(attemptOrder);
    createdAt = createdAt == null ? Instant.now() : createdAt;
  }
}
