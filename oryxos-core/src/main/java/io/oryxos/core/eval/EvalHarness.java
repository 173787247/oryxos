package io.oryxos.core.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Scores Agent/Skill/Prompt/Flow fixtures into {@link EvalMetrics} and compares against a baseline
 * (#472). Pure library — no LLM calls, no Spring wiring required.
 */
public final class EvalHarness {

  private EvalHarness() {}

  public static EvalMetrics score(List<EvalCase> cases) {
    Objects.requireNonNull(cases, "cases");
    if (cases.isEmpty()) {
      return EvalMetrics.empty();
    }
    int n = cases.size();
    int successes = 0;
    long latencySum = 0L;
    long costSum = 0L;
    double toolSum = 0.0;
    int toolDenom = 0;
    double citeSum = 0.0;
    int citeDenom = 0;

    for (EvalCase c : cases) {
      if (c.success()) {
        successes++;
      }
      latencySum += c.latencyMs();
      costSum += c.costMicros();
      if (!c.expectedTools().isEmpty()) {
        toolSum += overlapRatio(c.expectedTools(), c.actualTools());
        toolDenom++;
      }
      if (!c.expectedCitations().isEmpty()) {
        citeSum += overlapRatio(c.expectedCitations(), c.actualCitations());
        citeDenom++;
      }
    }

    double successRate = (double) successes / (double) n;
    double toolAccuracy = toolDenom == 0 ? 1.0 : toolSum / (double) toolDenom;
    double citationQuality = citeDenom == 0 ? 1.0 : citeSum / (double) citeDenom;
    double latencyAvg = (double) latencySum / (double) n;
    return new EvalMetrics(successRate, toolAccuracy, citationQuality, latencyAvg, costSum, n);
  }

  public static EvalMetricsDelta compare(EvalMetrics current, EvalMetrics baseline) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(baseline, "baseline");
    return new EvalMetricsDelta(
        current.successRate() - baseline.successRate(),
        current.toolAccuracy() - baseline.toolAccuracy(),
        current.citationQuality() - baseline.citationQuality(),
        current.latencyMsAvg() - baseline.latencyMsAvg(),
        current.costMicrosTotal() - baseline.costMicrosTotal());
  }

  public static EvalSuiteResult evaluate(
      String suiteName, List<EvalCase> cases, Optional<EvalBaseline> baseline) {
    Objects.requireNonNull(suiteName, "suiteName");
    Objects.requireNonNull(cases, "cases");
    Objects.requireNonNull(baseline, "baseline");
    EvalMetrics metrics = score(cases);
    Optional<EvalMetricsDelta> delta = baseline.map(b -> compare(metrics, b.metrics()));
    return new EvalSuiteResult(suiteName, metrics, cases, baseline, delta);
  }

  /** Intersection / expected size; empty expected yields 1.0. */
  static double overlapRatio(List<String> expected, List<String> actual) {
    if (expected.isEmpty()) {
      return 1.0;
    }
    Set<String> exp = new HashSet<>(expected);
    Set<String> act = new HashSet<>(actual);
    int hit = 0;
    for (String e : exp) {
      if (act.contains(e)) {
        hit++;
      }
    }
    return (double) hit / (double) exp.size();
  }
}
