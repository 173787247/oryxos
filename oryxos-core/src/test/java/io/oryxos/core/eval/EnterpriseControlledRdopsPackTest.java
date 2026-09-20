package io.oryxos.core.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.flow.DefaultFlowNodeHandler;
import io.oryxos.core.flow.FlowDocuments;
import io.oryxos.core.flow.FlowEngine;
import io.oryxos.core.flow.FlowNodeOutcome;
import io.oryxos.core.flow.FlowRun;
import io.oryxos.core.flow.FlowRunState;
import io.oryxos.core.flow.FlowStep;
import io.oryxos.core.flow.FlowStepState;
import io.oryxos.core.flow.FlowTimelineEvent;
import io.oryxos.core.flow.InMemoryFlowRunStore;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #480 / Epic #460：受控研发运维标杆包——默认无危险写、批准参数绑定、安全/成本/结果可审计。 */
class EnterpriseControlledRdopsPackTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-21T04:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("受控研发运维评测集：默认无写/批准参数/审计用例齐全且门禁通过")
  void controlledRdopsSuite_passesGateWithDenyParamsAndAuditCases() throws Exception {
    EvalSuiteResult suite =
        EvalFixtureLoader.loadSuiteFromClasspath("/evals/controlled-rdops-suite.json");
    assertThat(suite.suiteName()).isEqualTo("enterprise-controlled-rdops");
    assertThat(suite.metrics().caseCount()).isEqualTo(6);
    assertThat(suite.metrics().successRate()).isEqualTo(1.0);
    assertThat(suite.metrics().toolAccuracy()).isEqualTo(1.0);
    assertThat(suite.metrics().citationQuality()).isEqualTo(1.0);
    assertThat(suite.metrics().costMicrosTotal()).isEqualTo(1800L);

    Set<String> ids = suite.cases().stream().map(EvalCase::id).collect(Collectors.toSet());
    assertThat(ids)
        .contains(
            "default-no-dangerous-write",
            "approved-params-only",
            "denied-no-exec",
            "audit-security-cost-result");

    EvalGateDecision d =
        EvalRegressionGate.evaluate(
            suite.metrics(), EvalThresholds.defaults(), suite.baseline(), 0.05, 2_000.0);
    assertThat(d.passed()).isTrue();
  }

  @Test
  @DisplayName("rdops-approve-exec Flow 静态校验通过且 apply 绑定 approved_params")
  void rdopsApproveExecFlow_validatesAndBindsApprovedParams() throws Exception {
    String md = readClasspath("/flows/rdops-approve-exec.flow.md");
    FlowDocuments.Result result = FlowDocuments.parseAndValidate(md);
    assertThat(result.ok()).as(result.diagnostics()::toString).isTrue();
    assertThat(result.definition().id()).isEqualTo("rdops-approve-exec");
    assertThat(result.definition().nodes().get("review").type().name()).isEqualTo("HUMAN");
    assertThat(result.definition().nodes().get("apply").ref()).isEqualTo("ops.exec");
    assertThat(result.definition().nodes().get("apply").inputs().get("params").from())
        .isEqualTo("review.approved_params");
    assertThat(result.definition().nodes().get("apply").inputs().get("params").from())
        .isNotEqualTo("prepare.plan");
  }

  @Test
  @DisplayName("solutions 包与 classpath 评测集保持一致")
  void solutionsPack_mirrorsClasspathSuite() throws Exception {
    Path packSuite =
        Path.of("..")
            .resolve(
                "solutions/enterprise-controlled-rdops/workspace/evals/controlled-rdops-suite.json")
            .toAbsolutePath()
            .normalize();
    if (!Files.isRegularFile(packSuite)) {
      packSuite =
          Path.of(
                  "solutions/enterprise-controlled-rdops/workspace/evals/controlled-rdops-suite.json")
              .toAbsolutePath()
              .normalize();
    }
    assertThat(packSuite).exists();
    EvalSuiteResult fromPack = EvalFixtureLoader.loadSuite(packSuite);
    EvalSuiteResult fromCp =
        EvalFixtureLoader.loadSuiteFromClasspath("/evals/controlled-rdops-suite.json");
    assertThat(fromPack.suiteName()).isEqualTo(fromCp.suiteName());
    assertThat(fromPack.metrics().caseCount()).isEqualTo(fromCp.metrics().caseCount());
    assertThat(fromPack.metrics().costMicrosTotal()).isEqualTo(fromCp.metrics().costMicrosTotal());
  }

  @Test
  @DisplayName("批准后仅执行 approved_params，忽略 Agent 原始危险草案")
  void apply_usesOnlyApprovedParams() throws Exception {
    String md = readClasspath("/flows/rdops-approve-exec.flow.md");
    AtomicInteger applyCalls = new AtomicInteger();
    AtomicReference<Object> seenParams = new AtomicReference<>();
    FlowEngine engine =
        new FlowEngine(
            new InMemoryFlowRunStore(),
            (node, inputs, run) -> {
              if ("apply".equals(node.id())) {
                applyCalls.incrementAndGet();
                seenParams.set(inputs.get("params"));
                return FlowNodeOutcome.succeeded(
                    Map.of("result", "executed:" + inputs.get("params")));
              }
              return new DefaultFlowNodeHandler().execute(node, inputs, run);
            },
            clock,
            true);

    FlowRun waiting = engine.start(md, Map.of("change", "kubectl delete ns prod --force"));
    assertThat(waiting.state()).isEqualTo(FlowRunState.WAITING);
    assertThat(waiting.currentNodeId()).isEqualTo("review");
    assertThat(applyCalls.get()).isZero();

    FlowRun done =
        engine.completeWaiting(
            waiting.id(),
            Map.of(
                "decision", "approved",
                "approved_params", "kubectl get pods -n prod"));
    assertThat(done.state()).isEqualTo(FlowRunState.SUCCEEDED);
    assertThat(applyCalls.get()).isEqualTo(1);
    assertThat(seenParams.get()).isEqualTo("kubectl get pods -n prod");
    assertThat(String.valueOf(seenParams.get())).doesNotContain("delete");
  }

  @Test
  @DisplayName("拒绝审批：走 abort，不产生 ops.exec 副作用")
  void denied_skipsExecSideEffect() throws Exception {
    String md = readClasspath("/flows/rdops-approve-exec.flow.md");
    AtomicInteger applyCalls = new AtomicInteger();
    FlowEngine engine =
        new FlowEngine(
            new InMemoryFlowRunStore(),
            (node, inputs, run) -> {
              if ("apply".equals(node.id())) {
                applyCalls.incrementAndGet();
                return FlowNodeOutcome.succeeded(Map.of("result", "executed"));
              }
              return new DefaultFlowNodeHandler().execute(node, inputs, run);
            },
            clock,
            true);

    FlowRun waiting = engine.start(md, Map.of("change", "dangerous deploy"));
    FlowRun done = engine.completeWaiting(waiting.id(), Map.of("decision", "denied"));
    assertThat(done.state()).isEqualTo(FlowRunState.SUCCEEDED);
    assertThat(applyCalls.get()).isZero();
    assertThat(
            engine.listSteps(done.id()).stream()
                .filter(s -> "apply".equals(s.nodeId()))
                .findFirst())
        .get()
        .extracting(FlowStep::state)
        .isEqualTo(FlowStepState.SKIPPED);
  }

  @Test
  @DisplayName("时间线可审计：决策、批准参数与执行结果可回放")
  void timeline_auditsSecurityAndResult() throws Exception {
    String md = readClasspath("/flows/rdops-approve-exec.flow.md");
    FlowEngine engine =
        new FlowEngine(
            new InMemoryFlowRunStore(),
            (node, inputs, run) -> {
              if ("apply".equals(node.id())) {
                return FlowNodeOutcome.succeeded(Map.of("result", "ok:" + inputs.get("params")));
              }
              return new DefaultFlowNodeHandler().execute(node, inputs, run);
            },
            clock,
            true);

    FlowRun waiting = engine.start(md, Map.of("change", "scale deployment"));
    FlowRun done =
        engine.completeWaiting(
            waiting.id(),
            Map.of(
                "decision", "approved",
                "approved_params", "kubectl scale deploy/api --replicas=2 -n staging"));
    assertThat(done.state()).isEqualTo(FlowRunState.SUCCEEDED);

    List<FlowTimelineEvent> events = engine.timeline(done.id());
    assertThat(events).isNotEmpty();
    assertThat(events.stream().map(FlowTimelineEvent::nodeId).collect(Collectors.toSet()))
        .contains("prepare", "review", "apply", "done");

    FlowStep review =
        engine.listSteps(done.id()).stream()
            .filter(s -> "review".equals(s.nodeId()))
            .findFirst()
            .orElseThrow();
    assertThat(review.outputsJson()).contains("approved");
    assertThat(review.outputsJson()).contains("kubectl scale");

    FlowStep apply =
        engine.listSteps(done.id()).stream()
            .filter(s -> "apply".equals(s.nodeId()))
            .findFirst()
            .orElseThrow();
    assertThat(apply.state()).isEqualTo(FlowStepState.SUCCEEDED);
    assertThat(apply.outputsJson()).contains("ok:kubectl scale");
  }

  private static String readClasspath(String path) throws Exception {
    try (InputStream in = EnterpriseControlledRdopsPackTest.class.getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
