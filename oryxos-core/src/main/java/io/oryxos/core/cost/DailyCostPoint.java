package io.oryxos.core.cost;

/**
 * 每日成本点（053 趋势折线）：{@code date} 为服务端本地时区自然日（ISO yyyy-MM-dd），{@code llmCostMicros} 为当日 LLM 成本（微元）。
 */
public record DailyCostPoint(String date, long llmCostMicros) {}
