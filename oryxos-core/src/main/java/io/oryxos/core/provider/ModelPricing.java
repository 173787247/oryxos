package io.oryxos.core.provider;

/** Model pricing VO (016 / 050): (provider, model) -> token unit prices + optional priceVersion. */
public record ModelPricing(
    String provider, String model, Double promptPrice, Double completionPrice, Long priceVersion) {

  public ModelPricing(String provider, String model, Double promptPrice, Double completionPrice) {
    this(provider, model, promptPrice, completionPrice, null);
  }
}
