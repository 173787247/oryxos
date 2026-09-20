package io.oryxos.core.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Eval / regression gate flags (#472). Defaults keep runtime unchanged — gate is opt-in.
 *
 * <pre>
 * oryxos.eval.gate-enabled=false
 * oryxos.eval.min-success-rate=0.80
 * </pre>
 */
@ConfigurationProperties(prefix = "oryxos.eval")
public class EvalProperties {

  /** When true, callers may refuse publish/start if gate fails. Default off. */
  private boolean gateEnabled = false;

  private double minSuccessRate = 0.80;
  private double minToolAccuracy = 0.80;
  private double minCitationQuality = 0.70;
  private double maxLatencyMsAvg = 10_000.0;
  private long maxCostMicrosTotal = 5_000_000L;
  private double maxRateRegression = 0.05;
  private double maxLatencyRegressionMs = 2_000.0;

  public boolean isGateEnabled() {
    return gateEnabled;
  }

  public void setGateEnabled(boolean gateEnabled) {
    this.gateEnabled = gateEnabled;
  }

  public double getMinSuccessRate() {
    return minSuccessRate;
  }

  public void setMinSuccessRate(double minSuccessRate) {
    this.minSuccessRate = minSuccessRate;
  }

  public double getMinToolAccuracy() {
    return minToolAccuracy;
  }

  public void setMinToolAccuracy(double minToolAccuracy) {
    this.minToolAccuracy = minToolAccuracy;
  }

  public double getMinCitationQuality() {
    return minCitationQuality;
  }

  public void setMinCitationQuality(double minCitationQuality) {
    this.minCitationQuality = minCitationQuality;
  }

  public double getMaxLatencyMsAvg() {
    return maxLatencyMsAvg;
  }

  public void setMaxLatencyMsAvg(double maxLatencyMsAvg) {
    this.maxLatencyMsAvg = maxLatencyMsAvg;
  }

  public long getMaxCostMicrosTotal() {
    return maxCostMicrosTotal;
  }

  public void setMaxCostMicrosTotal(long maxCostMicrosTotal) {
    this.maxCostMicrosTotal = maxCostMicrosTotal;
  }

  public double getMaxRateRegression() {
    return maxRateRegression;
  }

  public void setMaxRateRegression(double maxRateRegression) {
    this.maxRateRegression = maxRateRegression;
  }

  public double getMaxLatencyRegressionMs() {
    return maxLatencyRegressionMs;
  }

  public void setMaxLatencyRegressionMs(double maxLatencyRegressionMs) {
    this.maxLatencyRegressionMs = maxLatencyRegressionMs;
  }

  public EvalThresholds toThresholds() {
    return new EvalThresholds(
        minSuccessRate, minToolAccuracy, minCitationQuality, maxLatencyMsAvg, maxCostMicrosTotal);
  }
}
