package io.oryxos.storage;

/**
 * 窗口内 LLM 审计的一行聚合（016 看板）：只把计数与求和原料带回服务层，绝不把整窗实体读进堆。
 *
 * <p>各列在零行窗口下可能为 null（{@code count} 恒为 0）；{@code measuredCostCount} 是「有已计量成本的调用数」， 用来区分「未计量 → 成本
 * null」与「已计量但合计为 0 元」。
 */
public record LlmAuditTotals(
    Long totalCount,
    Long successCount,
    Long promptTokens,
    Long completionTokens,
    Long costMicros,
    Long measuredCostCount,
    Double avgDurationMs) {}
