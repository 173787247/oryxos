package io.oryxos.storage;

import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import java.util.Optional;

/** {@link PricingStore} 的 JPA 实现：llm_pricing 表 → {@link ModelPricing} 值对象。 */
public class JpaPricingStore implements PricingStore {

  private final LlmPricingRepository repository;

  public JpaPricingStore(LlmPricingRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<ModelPricing> find(String provider, String model) {
    return repository
        .findByProviderAndModel(provider, model)
        .map(
            e ->
                new ModelPricing(
                    e.getProvider(), e.getModel(), e.getPromptPrice(), e.getCompletionPrice()));
  }
}
