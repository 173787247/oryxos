package io.oryxos.core.routing;

/** Why a candidate was chosen, reordered, filtered, or used as fallback (#477). */
public enum RoutingReasonCode {
  DEFAULT,
  DIFFICULTY,
  SENSITIVITY_RESIDENCY,
  BUDGET,
  LATENCY,
  COST_PREFER_CHEAP,
  FALLBACK,
  FILTERED
}
