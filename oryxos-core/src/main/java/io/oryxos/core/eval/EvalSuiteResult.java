package io.oryxos.core.eval;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Scored suite plus optional baseline comparison (#472). */
public record EvalSuiteResult(
    String suiteName,
    EvalMetrics metrics,
    List<EvalCase> cases,
    Optional<EvalBaseline> baseline,
    Optional<EvalMetricsDelta> delta) {

  public EvalSuiteResult {
    Objects.requireNonNull(suiteName, "suiteName");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(cases, "cases");
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(delta, "delta");
    cases = List.copyOf(cases);
  }
}
