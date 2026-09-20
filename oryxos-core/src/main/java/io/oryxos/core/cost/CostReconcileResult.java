package io.oryxos.core.cost;

public record CostReconcileResult(
    String runId,
    long ledgerLlmCostMicros,
    long ledgerToolCostMicros,
    long auditLlmCostMicros,
    boolean matched,
    String detail) {}
