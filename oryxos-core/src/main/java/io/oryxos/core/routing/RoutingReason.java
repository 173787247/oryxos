package io.oryxos.core.routing;

/** Single explainable reason attached to a routing / fallback decision. */
public record RoutingReason(RoutingReasonCode code, String detail) {

  public RoutingReason {
    code = code == null ? RoutingReasonCode.DEFAULT : code;
    detail = detail == null ? "" : detail;
  }
}
