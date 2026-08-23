package io.oryxos.web.controller.dto;

/** 工具调用汇总（KPI 卡片）。 */
public record ToolSummaryView(
    long count, long successCount, double successRate, long avgDurationMs) {}
