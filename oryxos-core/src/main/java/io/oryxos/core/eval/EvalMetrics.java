package io.oryxos.core.eval;

/**
 * Aggregated suite metrics (#472 acceptance): success rate, tool accuracy, citation quality,
 * latency, cost.
 */
public record EvalMetrics(
    double successRate,
    double toolAccuracy,
    double citationQuality,
    double latencyMsAvg,
    long costMicrosTotal,
    int caseCount) {

  public EvalMetrics {
    if (caseCount < 0) {
      throw new IllegalArgumentException("caseCount must be >= 0");
    }
  }

  /** Empty suite — all rates 1.0 so an empty suite does not trip min-rate gates by accident. */
  public static EvalMetrics empty() {
    return new EvalMetrics(1.0, 1.0, 1.0, 0.0, 0L, 0);
  }
}
