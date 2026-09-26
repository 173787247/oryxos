package io.oryxos.core.eval;

import java.util.List;
import java.util.Objects;

/**
 * Recorded audit facts for one trace (or session) — input to {@link AuditEvalExporter}. No JPA;
 * callers map storage rows into this shape.
 */
public record AuditTraceObservation(
    String traceId,
    String agentName,
    boolean success,
    List<String> actualTools,
    long latencyMs,
    long costMicros) {

  public AuditTraceObservation {
    Objects.requireNonNull(traceId, "traceId");
    agentName = agentName == null || agentName.isBlank() ? "unknown-agent" : agentName.strip();
    actualTools = actualTools == null ? List.of() : List.copyOf(actualTools);
    if (latencyMs < 0) {
      throw new IllegalArgumentException("latencyMs must be >= 0");
    }
    if (costMicros < 0) {
      throw new IllegalArgumentException("costMicros must be >= 0");
    }
  }
}
