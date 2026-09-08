package io.oryxos.core.provider;

/**
 * 模型定价（跨模块值对象，016 审计看板）：(provider, model) → 输入/输出 token 单价（元/百万 token）。
 *
 * <p>{@code promptPrice} 对应输入 token 单价、{@code completionPrice} 对应输出 token 单价，二者均可空=未定价。 放 core 是因为
 * oryxos-provider（成本计算）、oryxos-web（定价 CRUD）、oryxos-storage（JPA 实现）都要认它。
 */
public record ModelPricing(
    String provider, String model, Double promptPrice, Double completionPrice) {}
