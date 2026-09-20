package io.oryxos.core.eval;

import java.util.List;
import java.util.Objects;

/** Pass/fail decision from {@link EvalRegressionGate} with human-readable reasons. */
public record EvalGateDecision(boolean passed, List<String> reasons) {

  public EvalGateDecision {
    Objects.requireNonNull(reasons, "reasons");
    reasons = List.copyOf(reasons);
  }

  public static EvalGateDecision pass() {
    return new EvalGateDecision(true, List.of());
  }

  public static EvalGateDecision fail(List<String> reasons) {
    return new EvalGateDecision(false, reasons);
  }
}
