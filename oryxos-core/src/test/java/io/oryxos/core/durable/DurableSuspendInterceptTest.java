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
import io.oryxos.core.policy.HighRiskActionType;
import io.oryxos.core.provider.ToolCallRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ToolExecutor：durable-suspend 开启时 REQUIRE → 挂起而非 stub 拒绝。 */
class DurableSuspendInterceptTest {

  @Test
  @DisplayName("durable开启_REQUIRE抛ApprovalSuspended_工具零执行")
  void require_withDurable_suspends() {
    OryxTool shell = mock(OryxTool.class);
    when(shell.getName()).thenReturn("shell");
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("shell", shell), auditor);
    InMemoryTaskCheckpointStore store = new InMemoryTaskCheckpointStore();
    DurableTaskService tasks =
        new DurableTaskService(
            store,
            Clock.fixed(Instant.parse("2026-09-20T02:00:00Z"), ZoneOffset.UTC),
            null,
            null,
            true);
    executor.setDurableTaskService(tasks);
    executor.setApprovalPolicy(
        (agent, tool, args) ->
            new ApprovalPolicyDecision(
                ApprovalOutcome.REQUIRE_APPROVAL,
                "1",
                "r1",
                HighRiskActionType.SHELL,
                List.of("admin"),
                60,
                "命中高风险审批规则（r1）"));

    assertThatThrownBy(
            () ->
                executor.execute(
                    "s1", "a1", new ToolCallRequest("c1", "shell", "{\"cmd\":\"ls\"}")))
        .isInstanceOf(ApprovalSuspendedException.class)
        .satisfies(
            ex -> {
              ApprovalSuspendedException s = (ApprovalSuspendedException) ex;
              assertThat(s.checkpointId()).isNotBlank();
              assertThat(store.findById(s.checkpointId())).isPresent();
            });
    verify(shell, never()).execute(any(JsonNode.class));
  }

  @Test
  @DisplayName("durable关闭_REQUIRE仍stub拒绝")
  void require_withoutDurable_stubDeny() {
    OryxTool shell = mock(OryxTool.class);
    when(shell.getName()).thenReturn("shell");
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("shell", shell), auditor);
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

    ToolResult result = executor.execute("s1", "a1", new ToolCallRequest("c1", "shell", "{}"));
    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("需要人工审批");
    verify(shell, never()).execute(any(JsonNode.class));
  }
}
