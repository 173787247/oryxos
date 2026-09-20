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
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #479 / Epic #460：业务流程与工单标杆包——审批门禁、补偿幂等、时间线回放。 */
class EnterpriseProcessTicketPackTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T14:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("工单评测集：审批/补偿/回放用例齐全且门禁通过")
  void processTicketSuite_passesGateWithApprovalAndReplayCases() throws Exception {
    EvalSuiteResult suite =
        EvalFixtureLoader.loadSuiteFromClasspath("/evals/process-ticket-suite.json");
    assertThat(suite.suiteName()).isEqualTo("enterprise-process-ticket");
    assertThat(suite.metrics().caseCount()).isEqualTo(7);
    assertThat(suite.metrics().successRate()).isEqualTo(1.0);
    assertThat(suite.metrics().toolAccuracy()).isEqualTo(1.0);
    assertThat(suite.metrics().citationQuality()).isEqualTo(1.0);

    Set<String> ids = suite.cases().stream().map(EvalCase::id).collect(Collectors.toSet());
    assertThat(ids)
        .contains(
            "approval-gate",
            "denied-no-side-effect",
            "compensate-once",
            "idempotent-resume",
            "timeline-replay");

    EvalGateDecision d =
        EvalRegressionGate.evaluate(
            suite.metrics(), EvalThresholds.defaults(), suite.baseline(), 0.05, 2_000.0);
    assertThat(d.passed()).isTrue();
  }

  @Test
  @DisplayName("ticket-approve-apply Flow 静态校验通过")
  void ticketApproveApplyFlow_validates() throws Exception {
    String md = readClasspath("/flows/ticket-approve-apply.flow.md");
    FlowDocuments.Result result = FlowDocuments.parseAndValidate(md);
    assertThat(result.ok()).as(result.diagnostics()::toString).isTrue();
    assertThat(result.definition().id()).isEqualTo("ticket-approve-apply");
    assertThat(result.definition().nodes().get("apply").compensate()).isEqualTo("rollback");
    assertThat(result.definition().nodes().get("review").type().name()).isEqualTo("HUMAN");
  }

  @Test
  @DisplayName("solutions 包与 classpath 评测集保持一致")
  void solutionsPack_mirrorsClasspathSuite() throws Exception {
    Path packSuite =
        Path.of("..")
            .resolve(
                "solutions/enterprise-process-ticket/workspace/evals/process-ticket-suite.json")
            .toAbsolutePath()
            .normalize();
    if (!Files.isRegularFile(packSuite)) {
      packSuite =
          Path.of("solutions/enterprise-process-ticket/workspace/evals/process-ticket-suite.json")
              .toAbsolutePath()
              .normalize();
    }
    assertThat(packSuite).exists();
    EvalSuiteResult fromPack = EvalFixtureLoader.loadSuite(packSuite);
    EvalSuiteResult fromCp =
        EvalFixtureLoader.loadSuiteFromClasspath("/evals/process-ticket-suite.json");
    assertThat(fromPack.suiteName()).isEqualTo(fromCp.suiteName());
    assertThat(fromPack.metrics().caseCount()).isEqualTo(fromCp.metrics().caseCount());
    assertThat(fromPack.metrics().citationQuality()).isEqualTo(fromCp.metrics().citationQuality());
  }

  @Test
  @DisplayName("关键动作：未审批前停在 WAITING，批准后才 apply")
  void criticalApply_requiresHumanApproval() throws Exception {
    String md = readClasspath("/flows/ticket-approve-apply.flow.md");
    AtomicInteger applyCalls = new AtomicInteger();
    FlowEngine engine =
        new FlowEngine(
            new InMemoryFlowRunStore(),
            (node, inputs, run) -> {
              if ("apply".equals(node.id())) {
                applyCalls.incrementAndGet();
                return FlowNodeOutcome.succeeded(Map.of("result", "applied"));
              }
              if ("rollback".equals(node.id())) {
                return FlowNodeOutcome.succeeded(Map.of("result", "rolled-back"));
              }
              return new DefaultFlowNodeHandler().execute(node, inputs, run);
            },
            clock,
            true);

    FlowRun waiting = engine.start(md, Map.of("change", "provision read-only access"));
    assertThat(waiting.state()).isEqualTo(FlowRunState.WAITING);
    assertThat(waiting.currentNodeId()).isEqualTo("review");
    assertThat(applyCalls.get()).isZero();

    FlowRun done = engine.completeWaiting(waiting.id(), Map.of("decision", "approved"));
    assertThat(done.state()).isEqualTo(FlowRunState.SUCCEEDED);
    assertThat(applyCalls.get()).isEqualTo(1);
    assertThat(
            engine.listSteps(done.id()).stream()
                .filter(s -> "apply".equals(s.nodeId()))
                .findFirst())
        .get()
        .extracting(FlowStep::state)
        .isEqualTo(FlowStepState.SUCCEEDED);
  }

  @Test
  @DisplayName("拒绝审批：走 abort，不产生 apply 副作用")
  void denied_skipsApplySideEffect() throws Exception {
    String md = readClasspath("/flows/ticket-approve-apply.flow.md");
    AtomicInteger applyCalls = new AtomicInteger();
    FlowEngine engine =
        new FlowEngine(
            new InMemoryFlowRunStore(),
            (node, inputs, run) -> {
              if ("apply".equals(node.id())) {
                applyCalls.incrementAndGet();
                return FlowNodeOutcome.succeeded(Map.of("result", "applied"));
              }
              return new DefaultFlowNodeHandler().execute(node, inputs, run);
            },
            clock,
            true);

    FlowRun waiting = engine.start(md, Map.of("change", "sensitive write"));
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
  @DisplayName("apply 失败时补偿 rollback 只执行一次")
  void applyFailure_compensatesOnce() throws Exception {
    String md = readClasspath("/flows/ticket-approve-apply.flow.md");
    AtomicInteger rollbackCalls = new AtomicInteger();
    FlowEngine engine =
        new FlowEngine(
            new InMemoryFlowRunStore(),
            (node, inputs, run) -> {
              if ("apply".equals(node.id())) {
                return FlowNodeOutcome.failed("downstream rejected");
              }
              if ("rollback".equals(node.id())) {
                rollbackCalls.incrementAndGet();
                return FlowNodeOutcome.succeeded(Map.of("result", "rolled-back"));
              }
              return new DefaultFlowNodeHandler().execute(node, inputs, run);
            },
            clock,
            true,
            0,
            true);

    FlowRun waiting = engine.start(md, Map.of("change", "provision"));
    FlowRun failed = engine.completeWaiting(waiting.id(), Map.of("decision", "approved"));
    assertThat(failed.state()).isEqualTo(FlowRunState.FAILED);
    assertThat(rollbackCalls.get()).isEqualTo(1);
    assertThat(
            engine.listSteps(failed.id()).stream()
                .anyMatch(s -> s.idempotencyKey().endsWith(":compensate")))
        .isTrue();
  }

  @Test
  @DisplayName("时间线可回放 triage → review → apply → done")
  void timeline_replaysEndToEndChain() throws Exception {
    String md = readClasspath("/flows/ticket-approve-apply.flow.md");
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, true);
    FlowRun waiting = engine.start(md, Map.of("change", "open ticket T-1"));
    FlowRun done = engine.completeWaiting(waiting.id(), Map.of("decision", "approved"));
    assertThat(done.state()).isEqualTo(FlowRunState.SUCCEEDED);

    List<FlowTimelineEvent> events = engine.timeline(done.id());
    assertThat(events).isNotEmpty();
    assertThat(events.stream().map(FlowTimelineEvent::nodeId).collect(Collectors.toSet()))
        .contains("triage", "review", "apply", "done");
    assertThat(events.stream().anyMatch(e -> e.state() == FlowStepState.SUCCEEDED)).isTrue();
    // fixed Clock → step/timeline secondary-sort by nodeId; assert membership of e2e chain
    assertThat(
            engine.listSteps(done.id()).stream()
                .filter(s -> s.state() == FlowStepState.SUCCEEDED)
                .map(FlowStep::nodeId)
                .collect(Collectors.toSet()))
        .containsExactlyInAnyOrder("triage", "review", "apply", "done");
  }

  private static String readClasspath(String path) throws Exception {
    try (InputStream in = EnterpriseProcessTicketPackTest.class.getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
