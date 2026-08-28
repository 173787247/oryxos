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
 * 020 验收 harness：PolicyInterceptTest——两处拦截点口径钉死（R2）。守：事前（PromptBuilder 过滤后模型清单
 * 不含被拒工具）、事中（ToolExecutor 拒绝时工具零执行且审计落 blockedBy='policy'）、ALLOW_ALL/未注入时两点
 * 行为与现状完全等价（零策略零破坏，SC-001）。
 */
class PolicyInterceptTest {

  private static final ToolPolicyService DENY_SHELL =
      (agent, tool) ->
          "shell".equals(tool)
              ? ToolPolicyService.PolicyDecision.denied("命中全局禁用规则（shell）")
              : ToolPolicyService.PolicyDecision.ALLOWED;

  @Test
  @DisplayName("事前_被拒工具不进模型清单_其余照常")
  void promptBuilder_filtersDeniedTools() {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.load(any())).thenReturn("sys");
    PromptBuilder builder =
        new PromptBuilder(
            contextLoader, Map.of("shell", tool("shell"), "read_file", tool("read_file")));
    builder.setToolPolicy(DENY_SHELL);

    ProviderRequest request = builder.build(new Session("s-1", "a1"), profile("a1"));

    assertThat(request.availableTools()).extracting(OryxTool::getName).containsExactly("read_file");
  }

  @Test
  @DisplayName("事前_未注入策略_清单与现状一致（零破坏）")
  void promptBuilder_defaultAllowAll() {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.load(any())).thenReturn("sys");
    PromptBuilder builder =
        new PromptBuilder(
            contextLoader, Map.of("shell", tool("shell"), "read_file", tool("read_file")));

    ProviderRequest request = builder.build(new Session("s-1", "a1"), profile("a1"));

    assertThat(request.availableTools())
        .extracting(OryxTool::getName)
        .containsExactlyInAnyOrder("shell", "read_file");
  }

  @Test
  @DisplayName("事中_策略拒绝_工具零执行_审计落blockedBy=policy_错误信息含命中规则")
  void toolExecutor_deniesWithAuditMark() {
    OryxTool shell = tool("shell");
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("shell", shell), auditor);
    executor.setToolPolicy(DENY_SHELL);

    ToolResult result = executor.execute("s-1", "a1", new ToolCallRequest("c1", "shell", "{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("被平台策略禁止").contains("命中全局禁用规则");
    verify(shell, never()).execute(any(JsonNode.class));
    verify(auditor)
        .record(
            eq("s-1"),
            eq("a1"),
            eq("shell"),
            anyString(),
            eq(null),
            eq(false),
            anyString(),
            eq("policy"),
            anyLong());
  }

  @Test
  @DisplayName("事中_未注入策略_执行与审计交互与现状完全等价（零破坏）")
  void toolExecutor_defaultAllowAll() {
    OryxTool shell = tool("shell");
    when(shell.execute(any(JsonNode.class))).thenReturn(new ToolResult(true, "ok", null, false));
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("shell", shell), auditor);

    ToolResult result = executor.execute("s-1", "a1", new ToolCallRequest("c1", "shell", "{}"));

    assertThat(result.success()).isTrue();
    verify(shell).execute(any(JsonNode.class));
    // 现状交互契约：成功路径走 8 参 record（无 blockedBy）
    verify(auditor)
        .record(
            eq("s-1"), eq("a1"), eq("shell"), anyString(), eq("ok"), eq(true), eq(null), anyLong());
  }

  private static OryxTool tool(String name) {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn(name);
    when(tool.getDescription()).thenReturn(name);
    return tool;
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        "d",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of("shell", "read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(5, 20));
  }
}
