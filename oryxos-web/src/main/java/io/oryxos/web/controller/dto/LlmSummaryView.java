package io.oryxos.web.controller.dto;

/** LLM 调用汇总（KPI 卡片）。totalCostMicros 为 null 表示无任何已计量调用。 */
public record LlmSummaryView(
    long count,
    long successCount,
    double successRate,
    long totalPromptTokens,
    long totalCompletionTokens,
    Long totalCostMicros,
    long avgDurationMs) {}
