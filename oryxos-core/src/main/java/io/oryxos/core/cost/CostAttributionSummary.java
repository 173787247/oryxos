package io.oryxos.core.cost;

import java.util.List;

/** Attribution summary: detail rows + totals + price versions (report/reconcile). */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "Lists are List.copyOf snapshots; accessors return the unmodifiable copies.")
public final class CostAttributionSummary {

  private final List<CostLedgerEntry> entries;
  private final long llmCostMicros;
  private final long toolCostMicros;
  private final long totalCostMicros;
  private final long totalLatencyMs;
  private final long promptTokens;
  private final long completionTokens;
  private final List<Long> priceVersions;
  private final long llmCallCount;
  private final long toolCallCount;

  public CostAttributionSummary(
      List<CostLedgerEntry> entries,
      long llmCostMicros,
      long toolCostMicros,
      long totalCostMicros,
      long totalLatencyMs,
      long promptTokens,
      long completionTokens,
      List<Long> priceVersions,
      long llmCallCount,
      long toolCallCount) {
    this.entries = List.copyOf(entries == null ? List.of() : entries);
    this.llmCostMicros = llmCostMicros;
    this.toolCostMicros = toolCostMicros;
    this.totalCostMicros = totalCostMicros;
    this.totalLatencyMs = totalLatencyMs;
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.priceVersions = List.copyOf(priceVersions == null ? List.of() : priceVersions);
    this.llmCallCount = llmCallCount;
    this.toolCallCount = toolCallCount;
  }

  public List<CostLedgerEntry> entries() {
    return entries;
  }

  public long llmCostMicros() {
    return llmCostMicros;
  }

  public long toolCostMicros() {
    return toolCostMicros;
  }

  public long totalCostMicros() {
    return totalCostMicros;
  }

  public long totalLatencyMs() {
    return totalLatencyMs;
  }

  public long promptTokens() {
    return promptTokens;
  }

  public long completionTokens() {
    return completionTokens;
  }

  public List<Long> priceVersions() {
    return priceVersions;
  }

  /** LLM 调用条目数（sourceKind=LLM）；KPI「LLM 调用次数」与平均延迟口径。 */
  public long llmCallCount() {
    return llmCallCount;
  }

  /** 工具调用条目数（sourceKind=TOOL）；KPI「工具调用次数」口径。 */
  public long toolCallCount() {
    return toolCallCount;
  }
}
