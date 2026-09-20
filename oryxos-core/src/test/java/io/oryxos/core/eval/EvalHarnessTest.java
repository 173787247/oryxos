package io.oryxos.core.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 048 / #472：评测聚合与基线对比。 */
class EvalHarnessTest {

  @Test
  @DisplayName("聚合成功率、工具正确率、引用质量、时延与成本")
  void score_aggregatesAcceptanceMetrics() {
    List<EvalCase> cases =
        List.of(
            new EvalCase(
                "a",
                EvalTargetKind.AGENT,
                "a",
                true,
                List.of("t1", "t2"),
                List.of("t1", "t2"),
                List.of("c1"),
                List.of("c1"),
                100,
                1000),
            new EvalCase(
                "b",
                EvalTargetKind.SKILL,
                "b",
                false,
                List.of("t1"),
                List.of("t2"),
                List.of("c1"),
                List.of(),
                300,
                2000));

    EvalMetrics m = EvalHarness.score(cases);
    assertThat(m.caseCount()).isEqualTo(2);
    assertThat(m.successRate()).isEqualTo(0.5);
    assertThat(m.toolAccuracy()).isEqualTo(0.5); // case a=1.0, case b=0.0
    assertThat(m.citationQuality()).isEqualTo(0.5); // a=1.0, b=0.0
    assertThat(m.latencyMsAvg()).isEqualTo(200.0);
    assertThat(m.costMicrosTotal()).isEqualTo(3000L);
  }

  @Test
  @DisplayName("变更可对比基线（正负 delta）")
  void compare_producesDeltasVsBaseline() {
    EvalMetrics current = new EvalMetrics(0.9, 0.8, 0.7, 500.0, 20_000L, 10);
    EvalMetrics baseline = new EvalMetrics(1.0, 0.9, 0.7, 400.0, 15_000L, 10);
    EvalMetricsDelta d = EvalHarness.compare(current, baseline);
    assertThat(d.successRateDelta()).isCloseTo(-0.1, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(d.toolAccuracyDelta()).isCloseTo(-0.1, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(d.citationQualityDelta()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(d.latencyMsAvgDelta()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(d.costMicrosTotalDelta()).isEqualTo(5_000L);
  }

  @Test
  @DisplayName("四类 target kind 均可进入 suite")
  void evaluate_acceptsAllTargetKinds() {
    List<EvalCase> cases =
        List.of(
            caseOf("1", EvalTargetKind.AGENT),
            caseOf("2", EvalTargetKind.SKILL),
            caseOf("3", EvalTargetKind.PROMPT),
            caseOf("4", EvalTargetKind.FLOW));
    EvalSuiteResult r = EvalHarness.evaluate("kinds", cases, Optional.empty());
    assertThat(r.metrics().caseCount()).isEqualTo(4);
    assertThat(r.metrics().successRate()).isEqualTo(1.0);
  }

  private static EvalCase caseOf(String id, EvalTargetKind kind) {
    return new EvalCase(id, kind, id, true, List.of(), List.of(), List.of(), List.of(), 10, 0);
  }
}
