package io.oryxos.storage;

/**
 * 按一个维度分组的 LLM 调用计数（016 看板）。
 *
 * <p>{@code key} 为该维度的原始值；为 null 表示「未归属」，由服务层映射成展示文案 —— 存储层不认识展示文案， 也不会把真叫「(未归属)」的 Agent
 * 合并进同一组。{@code measuredCostCount} 同 {@link LlmAuditTotals}。
 */
public record LlmAuditGroup(
    String key, Long totalCount, Long successCount, Long costMicros, Long measuredCostCount) {}
