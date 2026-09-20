package io.oryxos.core.cost;

import java.util.List;

/** Attribution summary: detail rows + totals + price versions (report/reconcile). */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "Immutable record of already-copied lists from CostLedgerService.query.")
public record CostAttributionSummary(
    List<CostLedgerEntry> entries,
    long llmCostMicros,
    long toolCostMicros,
    long totalCostMicros,
    long totalLatencyMs,
    long promptTokens,
    long completionTokens,
    List<Long> priceVersions) {}
