package io.oryxos.core.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.flow.FlowDocuments;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #478 / Epic #460：企业知识与支持标杆包——引用评测 + 转人工 Flow 静态校验。 */
class EnterpriseKnowledgeSupportPackTest {

  @Test
  @DisplayName("知识支持评测集：引用质量达标且含转人工无引用用例")
  void knowledgeSupportSuite_passesGateWithCitationsAndHandoff() throws Exception {
    EvalSuiteResult suite =
        EvalFixtureLoader.loadSuiteFromClasspath("/evals/knowledge-support-suite.json");
    assertThat(suite.suiteName()).isEqualTo("enterprise-knowledge-support");
    assertThat(suite.metrics().caseCount()).isEqualTo(6);
    assertThat(suite.metrics().successRate()).isEqualTo(1.0);
    assertThat(suite.metrics().toolAccuracy()).isEqualTo(1.0);
    assertThat(suite.metrics().citationQuality()).isEqualTo(1.0);

    Set<String> ids = suite.cases().stream().map(EvalCase::id).collect(Collectors.toSet());
    assertThat(ids)
        .contains("cite-password-reset", "cite-vpn", "no-evidence-escalate", "handoff-flow");

    EvalCase noEvidence =
        suite.cases().stream()
            .filter(c -> "no-evidence-escalate".equals(c.id()))
            .findFirst()
            .orElseThrow();
    assertThat(noEvidence.expectedCitations()).isEmpty();
    assertThat(noEvidence.actualCitations()).isEmpty();
    assertThat(noEvidence.success()).isTrue();

    EvalGateDecision d =
        EvalRegressionGate.evaluate(
            suite.metrics(), EvalThresholds.defaults(), suite.baseline(), 0.05, 2_000.0);
    assertThat(d.passed()).isTrue();
  }

  @Test
  @DisplayName("knowledge-handoff Flow 静态校验通过")
  void knowledgeHandoffFlow_validates() throws Exception {
    String md;
    try (InputStream in =
        EnterpriseKnowledgeSupportPackTest.class
            .getClassLoader()
            .getResourceAsStream("flows/knowledge-handoff.flow.md")) {
      assertThat(in).isNotNull();
      md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    FlowDocuments.Result result = FlowDocuments.parseAndValidate(md);
    assertThat(result.ok()).as(result.diagnostics()::toString).isTrue();
    assertThat(result.definition().id()).isEqualTo("knowledge-handoff");
  }

  @Test
  @DisplayName("solutions 包与 classpath 评测集保持一致")
  void solutionsPack_mirrorsClasspathSuite() throws Exception {
    Path packSuite =
        Path.of("..")
            .resolve(
                "solutions/enterprise-knowledge-support/workspace/evals/knowledge-support-suite.json")
            .toAbsolutePath()
            .normalize();
    if (!Files.isRegularFile(packSuite)) {
      // reactor / IDE cwd may already be repo root
      packSuite =
          Path.of(
                  "solutions/enterprise-knowledge-support/workspace/evals/knowledge-support-suite.json")
              .toAbsolutePath()
              .normalize();
    }
    assertThat(packSuite).exists();
    EvalSuiteResult fromPack = EvalFixtureLoader.loadSuite(packSuite);
    EvalSuiteResult fromCp =
        EvalFixtureLoader.loadSuiteFromClasspath("/evals/knowledge-support-suite.json");
    assertThat(fromPack.suiteName()).isEqualTo(fromCp.suiteName());
    assertThat(fromPack.metrics().caseCount()).isEqualTo(fromCp.metrics().caseCount());
    assertThat(fromPack.metrics().citationQuality()).isEqualTo(fromCp.metrics().citationQuality());
  }
}
