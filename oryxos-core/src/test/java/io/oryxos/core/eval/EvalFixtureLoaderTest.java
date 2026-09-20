package io.oryxos.core.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 048 / #472：fixture 加载覆盖 Agent/Skill/Prompt/Flow。 */
class EvalFixtureLoaderTest {

  @Test
  @DisplayName("sample suite 含四类 target 且可对比内嵌基线")
  void load_sampleSuite_coversFourKinds() throws Exception {
    EvalSuiteResult suite = EvalFixtureLoader.loadSuiteFromClasspath("/evals/sample-suite.json");
    assertThat(suite.suiteName()).isEqualTo("sample-regression-suite");
    assertThat(suite.metrics().caseCount()).isEqualTo(4);
    assertThat(suite.metrics().successRate()).isEqualTo(1.0);
    assertThat(suite.metrics().toolAccuracy()).isEqualTo(1.0);
    assertThat(suite.metrics().citationQuality()).isEqualTo(1.0);
    Set<EvalTargetKind> kinds =
        suite.cases().stream().map(EvalCase::kind).collect(Collectors.toSet());
    assertThat(kinds)
        .containsExactlyInAnyOrder(
            EvalTargetKind.AGENT, EvalTargetKind.SKILL, EvalTargetKind.PROMPT, EvalTargetKind.FLOW);
    assertThat(suite.baseline()).isPresent();
    assertThat(suite.delta()).isPresent();
  }
}
