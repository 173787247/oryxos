package io.oryxos.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 047 / #469 验收：人工等待/超时/取消/恢复、失败补偿、节点时间线。 */
class FlowHumanCompensateReplayTest {

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-20T08:00:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  @Test
  @DisplayName("人工节点 WAITING 后 completeWaiting 恢复")
  void human_waiting_thenComplete() throws Exception {
    String md = readResource("/flows/human-timeout.flow.md");
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, true);
    FlowRun waiting = engine.start(md, Map.of());
    assertThat(waiting.state()).isEqualTo(FlowRunState.WAITING);
    assertThat(waiting.currentNodeId()).isEqualTo("ask");
    FlowStep waitStep =
        engine.listSteps(waiting.id()).stream()
            .filter(s -> "ask".equals(s.nodeId()))
            .findFirst()
            .orElseThrow();
    assertThat(waitStep.expiresAt()).isEqualTo(now.get().plusSeconds(60));

    FlowRun done = engine.completeWaiting(waiting.id(), Map.of("answer", "ok"));
    assertThat(done.state()).isEqualTo(FlowRunState.SUCCEEDED);
  }

  @Test
  @DisplayName("人工等待超时后 expireWaiting 取消")
  void human_timeout_expiresWaiting() throws Exception {
    String md = readResource("/flows/human-timeout.flow.md");
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, true);
    FlowRun waiting = engine.start(md, Map.of());
    assertThat(waiting.state()).isEqualTo(FlowRunState.WAITING);

    now.set(now.get().plusSeconds(61));
    FlowRun cancelled = engine.expireWaiting(waiting.id());
    assertThat(cancelled.state()).isEqualTo(FlowRunState.CANCELLED);
    assertThat(cancelled.lastError()).contains("timed out");
    FlowStep step =
        engine.listSteps(waiting.id()).stream()
            .filter(s -> "ask".equals(s.nodeId()))
            .findFirst()
            .orElseThrow();
    assertThat(step.state()).isEqualTo(FlowStepState.CANCELLED);
  }

  @Test
  @DisplayName("cancelWaiting 取消人工等待")
  void human_cancelWaiting() throws Exception {
    String md = readResource("/flows/human-timeout.flow.md");
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, true);
    FlowRun waiting = engine.start(md, Map.of());
    FlowRun cancelled = engine.cancelWaiting(waiting.id(), "user aborted");
    assertThat(cancelled.state()).isEqualTo(FlowRunState.CANCELLED);
    assertThat(cancelled.lastError()).isEqualTo("user aborted");
    // idempotent
    assertThat(engine.cancelWaiting(waiting.id(), "again").state())
        .isEqualTo(FlowRunState.CANCELLED);
  }

  @Test
  @DisplayName("过期后 completeWaiting 走超时取消而非恢复")
  void human_expired_rejectsComplete() throws Exception {
    String md = readResource("/flows/human-timeout.flow.md");
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, true);
    FlowRun waiting = engine.start(md, Map.of());
    now.set(now.get().plusSeconds(120));
    FlowRun result = engine.completeWaiting(waiting.id(), Map.of("answer", "late"));
    assertThat(result.state()).isEqualTo(FlowRunState.CANCELLED);
  }

  @Test
  @DisplayName("失败节点在 compensation-enabled 时执行声明的补偿")
  void compensation_onFailure_runsDeclaredNode() throws Exception {
    String md = readResource("/flows/charge-refund.flow.md");
    AtomicInteger refundCalls = new AtomicInteger();
    FlowNodeHandler handler =
        (node, inputs, run) -> {
          if ("charge".equals(node.id())) {
            return FlowNodeOutcome.failed("payment declined");
          }
          if ("refund".equals(node.id())) {
            refundCalls.incrementAndGet();
            return FlowNodeOutcome.succeeded(Map.of("result", "refunded"));
          }
          return FlowNodeOutcome.failed("unexpected");
        };
    FlowEngine engine = new FlowEngine(new InMemoryFlowRunStore(), handler, clock, true, 0, true);
    FlowRun run = engine.start(md, Map.of());
    assertThat(run.state()).isEqualTo(FlowRunState.FAILED);
    assertThat(run.lastError()).isEqualTo("payment declined");
    assertThat(refundCalls.get()).isEqualTo(1);
    FlowStep compensate =
        engine.listSteps(run.id()).stream()
            .filter(s -> s.idempotencyKey().endsWith(":compensate"))
            .findFirst()
            .orElseThrow();
    assertThat(compensate.nodeId()).isEqualTo("refund");
    assertThat(compensate.state()).isEqualTo(FlowStepState.SUCCEEDED);
    assertThat(compensate.outputsJson()).contains("__compensatesFor");
  }

  @Test
  @DisplayName("compensation-enabled=false 时不跑补偿")
  void compensation_disabled_skipsCompensate() throws Exception {
    String md = readResource("/flows/charge-refund.flow.md");
    AtomicInteger refundCalls = new AtomicInteger();
    FlowNodeHandler handler =
        (node, inputs, run) -> {
          if ("charge".equals(node.id())) {
            return FlowNodeOutcome.failed("boom");
          }
          if ("refund".equals(node.id())) {
            refundCalls.incrementAndGet();
            return FlowNodeOutcome.succeeded(Map.of("result", "refunded"));
          }
          return FlowNodeOutcome.failed("unexpected");
        };
    FlowEngine engine = new FlowEngine(new InMemoryFlowRunStore(), handler, clock, true, 0, false);
    FlowRun run = engine.start(md, Map.of());
    assertThat(run.state()).isEqualTo(FlowRunState.FAILED);
    assertThat(refundCalls.get()).isZero();
    assertThat(engine.listSteps(run.id())).hasSize(1);
  }

  @Test
  @DisplayName("timeline 返回完整节点时间线")
  void timeline_listsOrderedNodeEvents() throws Exception {
    String md = readResource("/flows/hello-notify.flow.md");
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, true);
    FlowRun run = engine.start(md, Map.of("topic", "hi"));
    assertThat(run.state()).isEqualTo(FlowRunState.SUCCEEDED);
    List<FlowTimelineEvent> events = engine.timeline(run.id());
    assertThat(events).isNotEmpty();
    assertThat(events.stream().map(FlowTimelineEvent::nodeId).distinct())
        .containsExactly("draft", "send");
    assertThat(events.stream().anyMatch(e -> e.state() == FlowStepState.SUCCEEDED)).isTrue();
  }

  @Test
  @DisplayName("DSL 解析 timeoutSeconds 与 compensate")
  void dsl_parsesTimeoutAndCompensate() throws Exception {
    FlowDefinition human = FlowMarkdown.parse(readResource("/flows/human-timeout.flow.md"));
    assertThat(human.nodes().get("ask").timeoutSeconds()).isEqualTo(60);
    FlowDefinition charge = FlowMarkdown.parse(readResource("/flows/charge-refund.flow.md"));
    assertThat(charge.nodes().get("charge").compensate()).isEqualTo("refund");
    assertThat(FlowValidator.hasErrors(FlowValidator.validate(charge))).isFalse();
  }

  @Test
  @DisplayName("未知 compensate 目标静态校验失败")
  void validator_unknownCompensate() {
    String md =
        """
        ---
        apiVersion: oryxos.flow/v1
        kind: Flow
        id: bad-comp
        version: "1"
        entry: a
        nodes:
          a:
            type: tool
            compensate: missing
            outputs:
              result: { type: string }
        edges: []
        ---
        # bad
        """;
    FlowDefinition def = FlowMarkdown.parse(md);
    assertThat(
            FlowValidator.validate(def).stream().anyMatch(d -> d.code().equals("UNKNOWN_NODE_REF")))
        .isTrue();
  }

  @Test
  @DisplayName("engine 关闭时 timeline 拒绝")
  void disabled_rejectsTimeline() {
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, false);
    assertThatThrownBy(() -> engine.timeline("x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("engine-enabled");
  }

  private static String readResource(String path) throws Exception {
    try (var in = FlowHumanCompensateReplayTest.class.getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
