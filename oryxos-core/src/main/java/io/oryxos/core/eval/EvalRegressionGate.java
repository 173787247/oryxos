package io.oryxos.core.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Blocks release when suite metrics miss absolute thresholds or regress vs baseline (#472).
 *
 * <p>Default-off at runtime via {@link EvalProperties#isGateEnabled()}; CI invokes this from tests
 * / {@code scripts/eval-regression-gate.sh}.
 */
public final class EvalRegressionGate {

  private EvalRegressionGate() {}

  public static EvalGateDecision evaluate(EvalMetrics metrics, EvalThresholds thresholds) {
    return evaluate(metrics, thresholds, Optional.empty(), 0.0, 0.0);
  }

  /**
   * @param maxRateRegression max allowed drop vs baseline for rate metrics (e.g. 0.05 = 5pp)
   * @param maxLatencyRegressionMs max allowed latency increase vs baseline
   */
  public static EvalGateDecision evaluate(
      EvalMetrics metrics,
      EvalThresholds thresholds,
      Optional<EvalBaseline> baseline,
      double maxRateRegression,
      double maxLatencyRegressionMs) {
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(thresholds, "thresholds");
    Objects.requireNonNull(baseline, "baseline");
    List<String> reasons = new ArrayList<>();

    if (metrics.successRate() + 1e-9 < thresholds.minSuccessRate()) {
      reasons.add(
          String.format(
              "successRate %.4f < min %.4f", metrics.successRate(), thresholds.minSuccessRate()));
    }
    if (metrics.toolAccuracy() + 1e-9 < thresholds.minToolAccuracy()) {
      reasons.add(
          String.format(
              "toolAccuracy %.4f < min %.4f",
              metrics.toolAccuracy(), thresholds.minToolAccuracy()));
    }
    if (metrics.citationQuality() + 1e-9 < thresholds.minCitationQuality()) {
      reasons.add(
          String.format(
              "citationQuality %.4f < min %.4f",
              metrics.citationQuality(), thresholds.minCitationQuality()));
    }
    if (metrics.latencyMsAvg() - 1e-9 > thresholds.maxLatencyMsAvg()) {
      reasons.add(
          String.format(
              "latencyMsAvg %.1f > max %.1f",
              metrics.latencyMsAvg(), thresholds.maxLatencyMsAvg()));
    }
    if (metrics.costMicrosTotal() > thresholds.maxCostMicrosTotal()) {
      reasons.add(
          String.format(
              "costMicrosTotal %d > max %d",
              metrics.costMicrosTotal(), thresholds.maxCostMicrosTotal()));
    }

    baseline.ifPresent(
        b -> {
          EvalMetricsDelta d = EvalHarness.compare(metrics, b.metrics());
          if (d.successRateDelta() < -maxRateRegression) {
            reasons.add(
                String.format(
                    "successRate regressed by %.4f vs baseline (max %.4f)",
                    -d.successRateDelta(), maxRateRegression));
          }
          if (d.toolAccuracyDelta() < -maxRateRegression) {
            reasons.add(
                String.format(
                    "toolAccuracy regressed by %.4f vs baseline (max %.4f)",
                    -d.toolAccuracyDelta(), maxRateRegression));
          }
          if (d.citationQualityDelta() < -maxRateRegression) {
            reasons.add(
                String.format(
                    "citationQuality regressed by %.4f vs baseline (max %.4f)",
                    -d.citationQualityDelta(), maxRateRegression));
          }
          if (d.latencyMsAvgDelta() > maxLatencyRegressionMs) {
            reasons.add(
                String.format(
                    "latencyMsAvg increased by %.1f vs baseline (max %.1f)",
                    d.latencyMsAvgDelta(), maxLatencyRegressionMs));
          }
        });

    return reasons.isEmpty() ? EvalGateDecision.pass() : EvalGateDecision.fail(reasons);
  }
}
