package io.oryxos.core.eval;

import java.util.Objects;

/** Named metrics snapshot used as the regression comparison baseline (#472). */
public record EvalBaseline(String suiteName, String capturedAt, EvalMetrics metrics) {

  public EvalBaseline {
    Objects.requireNonNull(suiteName, "suiteName");
    Objects.requireNonNull(capturedAt, "capturedAt");
    Objects.requireNonNull(metrics, "metrics");
  }
}
