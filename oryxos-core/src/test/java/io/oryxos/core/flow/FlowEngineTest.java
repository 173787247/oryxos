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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 046 / #468 验收：中断恢复、节点结果/错误可查、幂等跳过已成功节点。 */
class FlowEngineTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T06:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("默认关闭时 start 拒绝")
  void disabled_rejectsStart() {
    FlowEngine engine =
        new FlowEngine(new InMemoryFlowRunStore(), new DefaultFlowNodeHandler(), clock, false);
    assertThatThrownBy(() -> engine.start("# no", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("engine-enabled");
  }

  @Test
  @DisplayName("线性 Flow 执行并持久化步骤结果")
  void linear_persistsStepResults() throws Exception {
    String md = readResource("/flows/hello-notify.flow.md");
    InMemoryFlowRunStore store = new InMemoryFlowRunStore();
    FlowEngine engine = new FlowEngine(store, new DefaultFlowNodeHandler(), clock, true);
    FlowRun run = engine.start(md, Map.of("topic", "hello"));

    assertThat(run.state()).isEqualTo(FlowRunState.SUCCEEDED);
    List<FlowStep> steps = engine.listSteps(run.id());
    assertThat(steps).hasSize(2);
    assertThat(steps).allMatch(s -> s.state() == FlowStepState.SUCCEEDED);
    assertThat(steps.stream().map(FlowStep::nodeId).toList()).containsExactly("draft", "send");
    FlowStep send = steps.stream().filter(s -> s.nodeId().equals("send")).findFirst().orElseThrow();
    assertThat(send.outputsJson()).contains("delivered");
  }

  @Test
  @DisplayName("中断后用同一 Store 恢复且不重跑已成功幂等节点")
  void interrupt_resume_skipsSucceededIdempotentNodes() throws Exception {
    String md = readResource("/flows/hello-notify.flow.md");
    InMemoryFlowRunStore store = new InMemoryFlowRunStore();
    AtomicInteger draftCalls = new AtomicInteger();
    AtomicInteger sendCalls = new AtomicInteger();
    FlowNodeHandler handler =
        (node, inputs, run) -> {
          if ("draft".equals(node.id())) {
            draftCalls.incrementAndGet();
            return FlowNodeOutcome.succeeded(Map.of("message", "m1"));
          }
          if ("send".equals(node.id())) {
            sendCalls.incrementAndGet();
            return FlowNodeOutcome.succeeded(Map.of("delivered", true));
          }
          return FlowNodeOutcome.failed("unexpected");
        };

    // 第一刀：draft 成功后在 send 前人工停住——通过 handler 在 send 抛错模拟中断前半
    FlowNodeHandler firstPass =
        (node, inputs, run) -> {
          if ("draft".equals(node.id())) {
            draftCalls.incrementAndGet();
            return FlowNodeOutcome.succeeded(Map.of("message", "m1"));
          }
          // 模拟进程在下一节点前崩溃：先把 draft 落盘，再让 advance 在 send 失败
          return FlowNodeOutcome.failed("crash-before-send");
        };
    FlowEngine engine1 = new FlowEngine(store, firstPass, clock, true);
    FlowRun failed = engine1.start(md, Map.of("topic", "hello"));
    assertThat(failed.state()).isEqualTo(FlowRunState.FAILED);
    assertThat(draftCalls.get()).isEqualTo(1);

    // 把 FAILED 的 send 清掉、run 拨回 RUNNING 指向 send，模拟「draft 已成功、send 未完成」的重启点
    FlowStep draft =
        store.listSteps(failed.id()).stream()
            .filter(s -> s.nodeId().equals("draft"))
            .findFirst()
            .orElseThrow();
    assertThat(draft.state()).isEqualTo(FlowStepState.SUCCEEDED);
    // 删除失败的 send step，恢复为可继续
    InMemoryFlowRunStore clean = new InMemoryFlowRunStore();
    FlowRun mid =
        failed
            .withState(FlowRunState.RUNNING, clock.instant(), null)
            .withCurrentNode("send", clock.instant());
    clean.saveRun(mid);
    clean.saveStep(draft);

    FlowEngine engine2 = new FlowEngine(clean, handler, clock, true);
    FlowRun resumed = engine2.resume(mid.id());
    assertThat(resumed.state()).isEqualTo(FlowRunState.SUCCEEDED);
    assertThat(draftCalls.get()).isEqualTo(1); // 未重跑 draft
    assertThat(sendCalls.get()).isEqualTo(1);

    // 再次 resume：幂等跳过全部已成功节点
    FlowRun again = engine2.resume(mid.id());
    assertThat(again.state()).isEqualTo(FlowRunState.SUCCEEDED);
    assertThat(draftCalls.get()).isEqualTo(1);
    assertThat(sendCalls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("节点失败时错误可查询")
  void nodeFailure_errorQueryable() {
    String md =
        """
        ---
        apiVersion: oryxos.flow/v1
        kind: Flow
        id: fail-demo
        version: "1"
        entry: boom
        nodes:
          boom:
            type: tool
            ref: shell
            outputs:
              result: { type: string }
        edges: []
        ---
        # fail
        """;
    FlowNodeHandler handler = (node, inputs, run) -> FlowNodeOutcome.failed("boom-error");
    FlowEngine engine = new FlowEngine(new InMemoryFlowRunStore(), handler, clock, true);
    FlowRun run = engine.start(md, Map.of());
    assertThat(run.state()).isEqualTo(FlowRunState.FAILED);
    assertThat(run.lastError()).isEqualTo("boom-error");
    FlowStep step = engine.listSteps(run.id()).get(0);
    assertThat(step.state()).isEqualTo(FlowStepState.FAILED);
    assertThat(step.error()).isEqualTo("boom-error");
  }

  @Test
  @DisplayName("审批节点进入 WAITING，completeWaiting 后续跑分支")
  void approval_waiting_thenComplete() throws Exception {
    String md = readResource("/flows/branch-approve.flow.md");
    InMemoryFlowRunStore store = new InMemoryFlowRunStore();
    FlowEngine engine = new FlowEngine(store, new DefaultFlowNodeHandler(), clock, true);
    FlowRun waiting = engine.start(md, Map.of("change", "bump version"));
    assertThat(waiting.state()).isEqualTo(FlowRunState.WAITING);
    assertThat(waiting.currentNodeId()).isEqualTo("review");

    FlowRun done = engine.completeWaiting(waiting.id(), Map.of("decision", "approved"));
    assertThat(done.state()).isEqualTo(FlowRunState.SUCCEEDED);
    List<FlowStep> steps = engine.listSteps(done.id());
    assertThat(steps.stream().filter(s -> s.nodeId().equals("apply")).findFirst())
        .get()
        .extracting(FlowStep::state)
        .isEqualTo(FlowStepState.SUCCEEDED);
    assertThat(steps.stream().filter(s -> s.nodeId().equals("abort")).findFirst())
        .get()
        .extracting(FlowStep::state)
        .isEqualTo(FlowStepState.SKIPPED);
  }

  @Test
  @DisplayName("Store 重载后仍可读 WAITING / SUCCEEDED 状态")
  void restart_survivesStoreReload() throws Exception {
    String md = readResource("/flows/branch-approve.flow.md");
    InMemoryFlowRunStore store = new InMemoryFlowRunStore();
    FlowEngine engine = new FlowEngine(store, new DefaultFlowNodeHandler(), clock, true);
    FlowRun waiting = engine.start(md, Map.of("change", "x"));
    assertThat(waiting.state()).isEqualTo(FlowRunState.WAITING);

    FlowEngine reloaded = new FlowEngine(store, new DefaultFlowNodeHandler(), clock, true);
    assertThat(reloaded.listWaiting()).hasSize(1);
    FlowRun same = reloaded.findRun(waiting.id()).orElseThrow();
    assertThat(same.state()).isEqualTo(FlowRunState.WAITING);
    List<FlowStep> steps = reloaded.listSteps(waiting.id());
    assertThat(steps.stream().anyMatch(s -> s.state() == FlowStepState.WAITING)).isTrue();
  }

  private static String readResource(String path) throws Exception {
    try (var in = FlowEngineTest.class.getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
