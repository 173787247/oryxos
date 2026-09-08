package io.oryxos.core.provider;

import java.util.Optional;

/**
 * 模型定价查询契约（016 审计看板）：成本计算按 (provider, model) 查价。
 *
 * <p>放 core 是依赖倒置——oryxos-provider 只认此接口，oryxos-storage 的 {@code JpaPricingStore} 实现。
 */
public interface PricingStore {

  Optional<ModelPricing> find(String provider, String model);
}
