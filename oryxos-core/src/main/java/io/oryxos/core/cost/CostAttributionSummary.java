package io.oryxos.core.cost;

import java.util.List;

public record CostAttributionSummary(
    List<CostLedgerEntry> entries,
    long llmCostMicros,
    long toolCostMicros,
    long totalCostMicros,
    long totalLatencyMs,
    long promptTokens,
    long completionTokens,
    List<Long> priceVersions) {}
