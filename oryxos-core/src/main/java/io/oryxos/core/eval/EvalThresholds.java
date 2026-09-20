package io.oryxos.core.eval;

/**
 * Release / CI gate thresholds. Defaults are permissive; tighten per suite or via {@code
 * oryxos.eval.*} properties.
 */
public record EvalThresholds(
    double minSuccessRate,
    double minToolAccuracy,
    double minCitationQuality,
    double maxLatencyMsAvg,
    long maxCostMicrosTotal) {

  public static EvalThresholds defaults() {
    return new EvalThresholds(0.80, 0.80, 0.70, 10_000.0, 5_000_000L);
  }
}
