package io.oryxos.core.eval;

/**
 * Current metrics minus baseline (positive rate delta = improvement; positive latency/cost delta =
 * regression).
 */
public record EvalMetricsDelta(
    double successRateDelta,
    double toolAccuracyDelta,
    double citationQualityDelta,
    double latencyMsAvgDelta,
    long costMicrosTotalDelta) {}
