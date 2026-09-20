package io.oryxos.core.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.policy.ApprovalAuditKind;
import io.oryxos.core.policy.ApprovalAuditRecorder;
import io.oryxos.core.policy.ApprovalOutcome;
import io.oryxos.core.policy.ApprovalPolicyDecision;
import io.oryxos.core.policy.ApprovalPolicyService;
import io.oryxos.core.policy.HighRiskActionType;
import io.oryxos.core.provider.ToolCallRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 044 / #466 验收：approve/deny/改参、过期与重复回调、回放字段。 */
class ApprovalInteractionServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T04:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("批准可改参后回放执行")
  void approve_withEditedArgs_replays() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService tasks = tasks(store);
    ApprovalSuspendedException suspended =
        tasks.suspendForApproval(
            "s1", "a1", new ToolCallRequest("c1", "shell", "{\"cmd\":\"old\"}"), decision(60));

    OryxTool shell = mock(OryxTool.class);
    when(shell.getName()).thenReturn("shell");
    when(shell.execute(any(JsonNode.class))).thenReturn(ToolResult.ok("ok"));
    ToolExecutor executor =
        new ToolExecutor(Map.of("shell", shell), mock(ToolInvocationAuditor.class));
    executor.setApprovalPolicy(ApprovalPolicyService.PASS_THROUGH);
    executor.setDurableTaskService(tasks);

    ApprovalInteractionService svc =
        interaction(
            tasks, new DurableTaskReplay(tasks, executor), store, ApprovalAuditRecorder.NOOP);

    ApprovalInteractionService.DecisionOutcome out =
        svc.decide(
            new ApprovalInteractionService.DecisionRequest(
                suspended.checkpointId(), true, "admin", "lgtm", "{\"cmd\":\"new\"}"));

    assertThat(out.checkpoint().state()).isEqualTo(DurableTaskState.SUCCEEDED);
    assertThat(out.checkpoint().argumentsJson()).contains("new");
    verify(shell).execute(any(JsonNode.class));
  }

  @Test
  @DisplayName("过期决策写TIMEOUT_DENIED且不执行")
  void expired_decide_timeoutDenied() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    // suspend at T0 with ttl=1 → expires at T0+1s; clock fixed at T0 so create then advance via
    // custom clock
    Clock start = Clock.fixed(Instant.parse("2026-09-20T03:00:00Z"), ZoneOffset.UTC);
    DurableTaskService tasks =
        new DurableTaskService(store, start, ApprovalPolicyService.PASS_THROUGH, true);
    ApprovalSuspendedException suspended =
        tasks.suspendForApproval("s1", "a1", new ToolCallRequest("c1", "shell", "{}"), decision(1));

    Clock later = Clock.fixed(Instant.parse("2026-09-20T03:00:02Z"), ZoneOffset.UTC);
    List<ApprovalAuditRecorder.ApprovalAuditEvent> events = new ArrayList<>();
    ApprovalAuditRecorder audit = events::add;
    OryxTool shell = mock(OryxTool.class);
    when(shell.getName()).thenReturn("shell");
    ToolExecutor executor =
        new ToolExecutor(Map.of("shell", shell), mock(ToolInvocationAuditor.class));
    ApprovalInteractionService svc =
        new ApprovalInteractionService(
            tasks,
            new DurableTaskReplay(tasks, executor),
            store,
            new InMemoryApprovalCallbackReceiptStore(),
            audit,
            later,
            true);

    ApprovalInteractionService.DecisionOutcome out =
        svc.decide(
            new ApprovalInteractionService.DecisionRequest(
                suspended.checkpointId(), true, "admin", null, null));

    assertThat(out.expired()).isTrue();
    assertThat(out.checkpoint().state()).isEqualTo(DurableTaskState.CANCELLED);
    assertThat(events)
        .extracting(ApprovalAuditRecorder.ApprovalAuditEvent::kind)
        .containsExactly(ApprovalAuditKind.TIMEOUT_DENIED);
    verify(shell, never()).execute(any());
  }

  @Test
  @DisplayName("重复IM回调安全不二次执行")
  void duplicateCallback_safe() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService tasks = tasks(store);
    ApprovalSuspendedException suspended =
        tasks.suspendForApproval(
            "s1", "a1", new ToolCallRequest("c1", "shell", "{}"), decision(60));

    OryxTool shell = mock(OryxTool.class);
    when(shell.getName()).thenReturn("shell");
    when(shell.execute(any(JsonNode.class))).thenReturn(ToolResult.ok("ok"));
    ToolExecutor executor =
        new ToolExecutor(Map.of("shell", shell), mock(ToolInvocationAuditor.class));
    executor.setApprovalPolicy(ApprovalPolicyService.PASS_THROUGH);
    executor.setDurableTaskService(tasks);
    ApprovalInteractionService svc =
        interaction(
            tasks, new DurableTaskReplay(tasks, executor), store, ApprovalAuditRecorder.NOOP);

    var req =
        new ApprovalInteractionService.DecisionRequest(
            suspended.checkpointId(), true, "feishu-user", "ok", null);
    var first = svc.handleCallback("feishu", "cb-1", req);
    var second = svc.handleCallback("feishu", "cb-1", req);

    assertThat(first.duplicate()).isFalse();
    assertThat(first.checkpoint().state()).isEqualTo(DurableTaskState.SUCCEEDED);
    assertThat(second.duplicate()).isTrue();
    verify(shell).execute(any(JsonNode.class));
  }

  @Test
  @DisplayName("详情可回放时效与状态")
  void detail_replayFields() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService tasks = tasks(store);
    ApprovalSuspendedException suspended =
        tasks.suspendForApproval(
            "s1", "a1", new ToolCallRequest("c1", "shell", "{}"), decision(120));
    ApprovalInteractionService svc =
        interaction(
            tasks,
            new DurableTaskReplay(
                tasks, new ToolExecutor(Map.of(), mock(ToolInvocationAuditor.class))),
            store,
            ApprovalAuditRecorder.NOOP);

    var detail = svc.detail(suspended.checkpointId()).orElseThrow();
    assertThat(detail.ttlSeconds()).isEqualTo(120);
    assertThat(detail.expiresAt()).isEqualTo(Instant.parse("2026-09-20T04:02:00Z"));
    assertThat(detail.state()).isEqualTo("WAITING_APPROVAL");
    assertThat(svc.listWaiting()).hasSize(1);
  }

  private DurableTaskService tasks(TaskCheckpointStore store) {
    return new DurableTaskService(store, clock, ApprovalPolicyService.PASS_THROUGH, true);
  }

  private ApprovalInteractionService interaction(
      DurableTaskService tasks,
      DurableTaskReplay replay,
      TaskCheckpointStore store,
      ApprovalAuditRecorder audit) {
    return new ApprovalInteractionService(
        tasks, replay, store, new InMemoryApprovalCallbackReceiptStore(), audit, clock, true);
  }

  private static ApprovalPolicyDecision decision(int ttl) {
    return new ApprovalPolicyDecision(
        ApprovalOutcome.REQUIRE_APPROVAL,
        "1",
        "r1",
        HighRiskActionType.SHELL,
        List.of("admin"),
        ttl,
        "need");
  }
}
