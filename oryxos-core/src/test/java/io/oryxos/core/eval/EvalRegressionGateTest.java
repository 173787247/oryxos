package io.oryxos.core.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 048 / #472：未达门槛阻止发布。 */
class EvalRegressionGateTest {

  @Test
  @DisplayName("达标 suite 通过门禁")
  void gate_passesWhenAboveThresholds() throws Exception {
    EvalSuiteResult suite = EvalFixtureLoader.loadSuiteFromClasspath("/evals/sample-suite.json");
    EvalGateDecision d =
        EvalRegressionGate.evaluate(
            suite.metrics(), EvalThresholds.defaults(), suite.baseline(), 0.05, 2_000.0);
    assertThat(d.passed()).isTrue();
    assertThat(d.reasons()).isEmpty();
  }

  @Test
  @DisplayName("未达门槛可阻止发布")
  void gate_blocksWhenBelowThresholds() throws Exception {
    EvalSuiteResult suite =
        EvalFixtureLoader.loadSuiteFromClasspath("/evals/regressing-suite.json");
    EvalGateDecision d =
        EvalRegressionGate.evaluate(
            suite.metrics(),
            new EvalThresholds(0.80, 0.80, 0.70, 1000.0, 50_000L),
            suite.baseline(),
            0.05,
            500.0);
    assertThat(d.passed()).isFalse();
    assertThat(d.reasons()).isNotEmpty();
    assertThat(d.reasons().stream().anyMatch(r -> r.contains("successRate"))).isTrue();
  }

  @Test
  @DisplayName("相对基线回退也会阻断")
  void gate_blocksOnBaselineRegression() {
    EvalMetrics current = new EvalMetrics(0.70, 1.0, 1.0, 100.0, 1000L, 10);
    EvalBaseline baseline =
        new EvalBaseline("b", "t", new EvalMetrics(1.0, 1.0, 1.0, 100.0, 1000L, 10));
    EvalGateDecision d =
        EvalRegressionGate.evaluate(
            current,
            new EvalThresholds(0.50, 0.50, 0.50, 10_000.0, 1_000_000L),
            Optional.of(baseline),
            0.05,
            2_000.0);
    assertThat(d.passed()).isFalse();
    assertThat(d.reasons().stream().anyMatch(r -> r.contains("regressed"))).isTrue();
  }

  @Test
  @DisplayName("gateEnabled 默认关闭——属性面零行为变化")
  void properties_gateDisabledByDefault() {
    EvalProperties props = new EvalProperties();
    assertThat(props.isGateEnabled()).isFalse();
    assertThat(props.toThresholds().minSuccessRate()).isEqualTo(0.80);
  }
}
