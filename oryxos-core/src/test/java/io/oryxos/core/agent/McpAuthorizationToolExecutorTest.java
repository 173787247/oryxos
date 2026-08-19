package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ToolCallRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 31 节验收：mcp_servers 白名单从死字段变成运行时真正生效——Agent 只能调它声明过的 server 提供的工具。 */
class McpAuthorizationToolExecutorTest {

  private OryxTool githubTool;
  private ToolInvocationAuditor auditor;
  private ProfileRegistry profileRegistry;

  @BeforeEach
  void setUp() {
    githubTool = mock(OryxTool.class);
    when(githubTool.getName()).thenReturn("github_search_issues");
    when(githubTool.execute(org.mockito.ArgumentMatchers.any())).thenReturn(ToolResult.ok("ok"));
    auditor = mock(ToolInvocationAuditor.class);
    profileRegistry = new ProfileRegistry();
  }

  private ToolExecutor executorWithOwnership() {
    return new ToolExecutor(
        Map.of("github_search_issues", githubTool),
        Map.of("github_search_issues", "github"),
        profileRegistry,
        auditor);
  }

  private static Profile profileWithMcpServers(String name, List<String> mcpServers) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of("github_search_issues"),
        mcpServers,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("Agent 声明了 mcp_servers: [github] -> 放行调用")
  void declaredServer_callAllowed() {
    profileRegistry.register(profileWithMcpServers("agent-a", List.of("github")));

    ToolResult result =
        executorWithOwnership()
            .execute("s-1", "agent-a", new ToolCallRequest("github_search_issues", "{}"));

    assertTrue(result.success());
  }

  @Test
  @DisplayName("Agent 未声明 mcp_servers -> 拒绝调用，即使 tools 里有这个工具名")
  void undeclaredServer_callDenied() {
    profileRegistry.register(profileWithMcpServers("agent-b", List.of())); // 没声明 github

    ToolResult result =
        executorWithOwnership()
            .execute("s-1", "agent-b", new ToolCallRequest("github_search_issues", "{}"));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("mcp_servers"));
  }

  @Test
  @DisplayName("旧 2-arg 构造（无 profileRegistry）不做 mcp_servers 校验，行为不变")
  void legacyConstructor_noEnforcement() {
    ToolExecutor legacy = new ToolExecutor(Map.of("github_search_issues", githubTool), auditor);

    ToolResult result =
        legacy.execute("s-1", "agent-anything", new ToolCallRequest("github_search_issues", "{}"));

    assertTrue(result.success());
  }

  @Test
  @DisplayName("构造后才写入的 mcp 归属立刻生效")
  void ownersAddedAfterConstructionAreEnforced() {
    Map<String, OryxTool> tools = new HashMap<>();
    tools.put("github_search_issues", githubTool);
    Map<String, String> owners = new HashMap<>();
    ToolExecutor executor = new ToolExecutor(tools, owners, profileRegistry, auditor);
    profileRegistry.register(profileWithMcpServers("agent-b", List.of()));

    assertTrue(
        executor
            .execute("s-1", "agent-b", new ToolCallRequest("github_search_issues", "{}"))
            .success(),
        "尚无归属时不当成 MCP 工具拦截");

    owners.put("github_search_issues", "github");

    ToolResult denied =
        executor.execute("s-1", "agent-b", new ToolCallRequest("github_search_issues", "{}"));
    assertFalse(denied.success());
    assertTrue(denied.errorMessage().contains("mcp_servers"));
  }
}
