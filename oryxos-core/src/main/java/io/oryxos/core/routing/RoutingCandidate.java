package io.oryxos.core.routing;

public record RoutingCandidate(String provider, String model) {
  public RoutingCandidate {
    provider = provider == null ? "" : provider;
    model = model == null ? "" : model;
  }
}
