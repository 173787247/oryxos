package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.session.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 042 验收：prompt 可见性与执行面共用同一 ApprovalPolicyService；PASS_THROUGH 零破坏；REQUIRE stub 拒绝 +
 * blocked_by=approval。
 */
class ApprovalInterceptTest {

  @Test
  @DisplayName("事前_DENY工具不进清单_REQUIRE仍可见")
  void prompt_denyHidden_requireVisible() {
    ApprovalPolicyService policy =
        (agent, tool, args) -> {
          if ("notify".equals(tool)) {
            return new ApprovalPolicyDecision(
                ApprovalOutcome.DENY,
                "1",
                "d1",
                HighRiskActionType.EXTERNAL_SEND,
                List.of(),
                60,
                "deny notify");
          }
          if ("shell".equals(tool)) {
            return new ApprovalPolicyDecision(
                ApprovalOutcome.REQUIRE_APPROVAL,
                "1",
                "r1",
                HighRiskActionType.SHELL,
                List.of("admin"),
                60,
                "need approve");
          }
          return ApprovalPolicyDecision.allow("1");
        };
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.load(any())).thenReturn("sys");
    PromptBuilder builder =
        new PromptBuilder(
            contextLoader,
            Map.of(
                "shell", tool("shell"),
                "notify", tool("notify"),
                "read_file", tool("read_file")));
    builder.setApprovalPolicy(policy);

    ProviderRequest request = builder.build(new Session("s-1", "a1"), profile("a1"));

    assertThat(request.availableTools())
        .extracting(OryxTool::getName)
        .containsExactlyInAnyOrder("shell", "read_file");
  }

  @Test
  @DisplayName("事中_REQUIRE_工具零执行_审计blockedBy=approval")
  void executor_requireApproval_blocksWithAudit() {
    OryxTool shell = tool("shell");
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("shell", shell), auditor);
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

    ToolResult result =
        executor.execute("s1", "a1", new ToolCallRequest("c1", "shell", "{\"cmd\":\"ls\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("需要人工审批");
    verify(shell, never()).execute(any(JsonNode.class));
    verify(auditor)
        .record(
            eq("s1"),
            eq("a1"),
            eq("shell"),
            anyString(),
            eq(null),
            eq(false),
            anyString(),
            eq("approval"),
            anyLong());
  }

  @Test
  @DisplayName("未注入审批策略_行为与现状一致")
  void passThrough_zeroChange() {
    OryxTool shell = tool("shell");
    when(shell.execute(any(JsonNode.class))).thenReturn(new ToolResult(true, "ok", null, false));
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("shell", shell), auditor);

    ToolResult result = executor.execute("s1", "a1", new ToolCallRequest("c1", "shell", "{}"));

    assertThat(result.success()).isTrue();
    verify(shell).execute(any(JsonNode.class));
  }

  private static OryxTool tool(String name) {
    OryxTool t = mock(OryxTool.class);
    when(t.getName()).thenReturn(name);
    when(t.getDescription()).thenReturn(name);
    return t;
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        "d",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of("shell", "notify", "read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(5, 20));
  }
}
