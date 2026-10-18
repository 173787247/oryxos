package io.oryxos.storage;

/** 窗口内工具调用审计的一行聚合（016 看板）：计数与平均耗时原料，不读整窗实体。 */
public record ToolAuditTotals(Long totalCount, Long successCount, Double avgDurationMs) {}
