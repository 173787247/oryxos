package io.oryxos.web.controller.dto;

import io.oryxos.storage.LlmPricing;

/** 模型定价视图（列表/详情返回）。 */
public record PricingView(
    Long id, String provider, String model, Double promptPrice, Double completionPrice) {

  public static PricingView from(LlmPricing e) {
    return new PricingView(
        e.getId(), e.getProvider(), e.getModel(), e.getPromptPrice(), e.getCompletionPrice());
  }
}
