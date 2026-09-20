package io.oryxos.core.cost;

import java.time.Instant;

/** Task-level cost ledger row (#476). */
public record CostLedgerEntry(
    long id,
    String runId,
    String taskId,
    String agentName,
    String teamId,
    String provider,
    String model,
    CostSourceKind sourceKind,
    String sourceRef,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    long llmCostMicros,
    long toolCostMicros,
    long latencyMs,
    Long priceVersion,
    String sessionId,
    String traceId,
    Instant createdAt) {

  public long totalCostMicros() {
    return llmCostMicros + toolCostMicros;
  }
}
