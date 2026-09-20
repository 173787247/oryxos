package io.oryxos.web.controller.dto;

import io.oryxos.storage.LlmPricing;

/** Model pricing view (list/detail). */
public record PricingView(
    Long id,
    String provider,
    String model,
    Double promptPrice,
    Double completionPrice,
    long priceVersion) {

  public static PricingView from(LlmPricing e) {
    return new PricingView(
        e.getId(),
        e.getProvider(),
        e.getModel(),
        e.getPromptPrice(),
        e.getCompletionPrice(),
        e.getPriceVersion());
  }
}
