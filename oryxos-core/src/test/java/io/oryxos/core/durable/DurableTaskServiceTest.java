package io.oryxos.core.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.oryxos.core.policy.ApprovalOutcome;
import io.oryxos.core.policy.ApprovalPolicyDecision;
import io.oryxos.core.policy.ApprovalPolicyService;
import io.oryxos.core.policy.HighRiskActionType;
import io.oryxos.core.provider.ToolCallRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 043 / #465 验收：状态持久、幂等键、检查点恢复回放。 */
class DurableTaskServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T02:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("挂起后新Store实例仍能读到WAITING_APPROVAL")
  void suspend_survivesStoreReload() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService svc = service(store, true);
    ApprovalPolicyDecision decision =
        new ApprovalPolicyDecision(
            ApprovalOutcome.REQUIRE_APPROVAL,
            "1",
            "r1",
            HighRiskActionType.SHELL,
            List.of("admin"),
            60,
            "need approve");

    ApprovalSuspendedException ex =
        svc.suspendForApproval(
            "s1", "agent-a", new ToolCallRequest("c1", "shell", "{\"cmd\":\"ls\"}"), decision);

    // 模拟重启：同一持久化、新门面
    DurableTaskService reloaded = service(store, true);
    assertThat(reloaded.listWaitingApproval()).hasSize(1);
    TaskCheckpoint cp = reloaded.findById(ex.checkpointId()).orElseThrow();
    assertThat(cp.state()).isEqualTo(DurableTaskState.WAITING_APPROVAL);
    assertThat(cp.idempotencyKey()).isEqualTo(ex.idempotencyKey());
    assertThat(cp.toolName()).isEqualTo("shell");
  }

  @Test
  @DisplayName("同一幂等键重试返回原检查点不新建")
  void retry_idempotentKey_returnsSameCheckpoint() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService svc = service(store, true);
    ApprovalPolicyDecision decision =
        new ApprovalPolicyDecision(
            ApprovalOutcome.REQUIRE_APPROVAL,
            "1",
            "r1",
            HighRiskActionType.SHELL,
            List.of(),
            60,
            "need");

    ApprovalSuspendedException first =
        svc.suspendForApproval("s1", "a1", new ToolCallRequest("c1", "shell", "{}"), decision);
    ApprovalSuspendedException second =
        svc.suspendForApproval("s1", "a1", new ToolCallRequest("c1", "shell", "{}"), decision);

    assertThat(second.checkpointId()).isEqualTo(first.checkpointId());
    assertThat(svc.retryWithIdempotencyKey(first.idempotencyKey()).id())
        .isEqualTo(first.checkpointId());
    assertThat(store.listByState(DurableTaskState.WAITING_APPROVAL)).hasSize(1);
  }

  @Test
  @DisplayName("批准后从检查点回放执行工具且决策幂等")
  void resume_fromCheckpoint_replaysTool_idempotent() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService svc = service(store, true);
    ApprovalSuspendedException suspended =
        svc.suspendForApproval(
            "s1",
            "a1",
            new ToolCallRequest("c1", "shell", "{\"cmd\":\"echo\"}"),
            new ApprovalPolicyDecision(
                ApprovalOutcome.REQUIRE_APPROVAL,
                "1",
                "r1",
                HighRiskActionType.SHELL,
                List.of(),
                60,
                "need"));

    OryxTool shell = mock(OryxTool.class);
    when(shell.getName()).thenReturn("shell");
    when(shell.execute(any(JsonNode.class))).thenReturn(new ToolResult(true, "ok", null, false));
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("shell", shell), auditor);
    // 回放时审批闸应被 grant 跳过；即便策略仍要求审批也不应再挂起
    executor.setApprovalPolicy(
        (agent, tool, args) ->
            new ApprovalPolicyDecision(
                ApprovalOutcome.REQUIRE_APPROVAL,
                "1",
                "r1",
                HighRiskActionType.SHELL,
                List.of(),
                60,
                "need"));
    executor.setDurableTaskService(svc);

    DurableTaskReplay replay = new DurableTaskReplay(svc, executor);
    DurableTaskReplay.ReplayOutcome once =
        replay.resume(suspended.checkpointId(), true, "admin", "lgtm");
    assertThat(once.checkpoint().state()).isEqualTo(DurableTaskState.SUCCEEDED);
    assertThat(once.toolResult().success()).isTrue();
    verify(shell).execute(any(JsonNode.class));

    DurableTaskReplay.ReplayOutcome twice =
        replay.resume(suspended.checkpointId(), true, "admin", "again");
    assertThat(twice.alreadyDone()).isTrue();
    assertThat(twice.checkpoint().state()).isEqualTo(DurableTaskState.SUCCEEDED);
    verify(shell).execute(any(JsonNode.class)); // still once
  }

  @Test
  @DisplayName("拒绝审批进入CANCELLED且不执行工具")
  void deny_cancelsWithoutExecuting() {
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService svc = service(store, true);
    ApprovalSuspendedException suspended =
        svc.suspendForApproval(
            "s1",
            "a1",
            new ToolCallRequest("c1", "shell", "{}"),
            new ApprovalPolicyDecision(
                ApprovalOutcome.REQUIRE_APPROVAL,
                "1",
                "r1",
                HighRiskActionType.SHELL,
                List.of(),
                60,
                "need"));

    OryxTool shell = mock(OryxTool.class);
    when(shell.getName()).thenReturn("shell");
    ToolExecutor executor =
        new ToolExecutor(Map.of("shell", shell), mock(ToolInvocationAuditor.class));
    DurableTaskReplay replay = new DurableTaskReplay(svc, executor);

    DurableTaskReplay.ReplayOutcome out =
        replay.resume(suspended.checkpointId(), false, "admin", "nope");
    assertThat(out.checkpoint().state()).isEqualTo(DurableTaskState.CANCELLED);
    verify(shell, never()).execute(any());
  }

  @Test
  @DisplayName("未知幂等键重试抛错")
  void retry_unknownKey_throws() {
    DurableTaskService svc = service(new InMemoryTaskCheckpointStore(), true);
    assertThatThrownBy(() -> svc.retryWithIdempotencyKey("missing"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private DurableTaskService service(TaskCheckpointStore store, boolean enabled) {
    return new DurableTaskService(store, clock, ApprovalPolicyService.PASS_THROUGH, null, enabled);
  }
}
