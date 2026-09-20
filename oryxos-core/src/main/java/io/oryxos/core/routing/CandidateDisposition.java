package io.oryxos.core.routing;

/** Per-candidate outcome for explainability. */
public record CandidateDisposition(
    String provider, String model, String status, RoutingReason reason) {

  public static CandidateDisposition selected(String provider, String model, RoutingReason reason) {
    return new CandidateDisposition(provider, model, "SELECTED", reason);
  }

  public static CandidateDisposition ordered(String provider, String model, RoutingReason reason) {
    return new CandidateDisposition(provider, model, "ORDERED", reason);
  }

  public static CandidateDisposition filtered(String provider, String model, RoutingReason reason) {
    return new CandidateDisposition(provider, model, "FILTERED", reason);
  }
}
