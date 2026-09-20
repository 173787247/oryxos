package io.oryxos.core.cost;

import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.Usage;
import java.util.Objects;

/** Decorates LlmCallAuditor: after successful audit write, append cost ledger when enabled. */
public final class CostAwareLlmCallAuditor implements LlmCallAuditor {

  private final LlmCallAuditor delegate;
  private final CostLedgerService ledger;
  private final PricingStore pricingStore;

  public CostAwareLlmCallAuditor(
      LlmCallAuditor delegate, CostLedgerService ledger, PricingStore pricingStore) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.ledger = ledger;
    this.pricingStore = pricingStore;
  }

  @Override
  public void record(
      String sessionId,
      String profileName,
      String provider,
      String model,
      Usage usage,
      Long costMicros,
      boolean success,
      String errorMessage,
      long durationMs) {
    delegate.record(
        sessionId,
        profileName,
        provider,
        model,
        usage,
        costMicros,
        success,
        errorMessage,
        durationMs);
    if (ledger == null || !ledger.isEnabled() || !success) {
      return;
    }
    Long priceVersion =
        pricingStore == null
            ? null
            : pricingStore.find(provider, model).map(p -> p.priceVersion()).orElse(null);
    ledger.recordLlm(
        sessionId, profileName, provider, model, usage, costMicros, priceVersion, durationMs);
  }
}
