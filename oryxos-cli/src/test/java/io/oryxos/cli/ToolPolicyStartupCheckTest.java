package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.OryxTool;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.storage.ToolPolicyRule;
import io.oryxos.storage.ToolPolicyRuleRepository;
import io.oryxos.storage.ToolPolicyServiceImpl;
import io.oryxos.tool.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 020 验收 harness：ToolPolicyStartupCheckTest——加载期告警口径钉死（FR-008/FR-011）。守：未知工具/未知 server 通配/不存在 Agent
 * 三类告警、有效集全空告警、正常策略无 WARN、run() 恒不抛。
 */
class ToolPolicyStartupCheckTest {

  private ToolPolicyRuleRepository repository;
  private ProfileRegistry profileRegistry;
  private ToolRegistry toolRegistry;
  private ToolPolicyStartupCheck check;
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;
  private List<ToolPolicyRule> rules;

  @BeforeEach
  void setUp() {
    repository = mock(ToolPolicyRuleRepository.class);
    rules = new java.util.ArrayList<>();
    when(repository.findAll()).thenReturn(rules);
    profileRegistry = new ProfileRegistry();
    toolRegistry = new ToolRegistry();
    toolRegistry.register(stubTool("shell"));
    toolRegistry.register(stubTool("read_file"));
    ToolPolicyServiceImpl store =
        new ToolPolicyServiceImpl(repository, name -> toolRegistry.mcpToolOwners().get(name));
    check = new ToolPolicyStartupCheck(store, profileRegistry, toolRegistry);
    logger = (Logger) LoggerFactory.getLogger(ToolPolicyStartupCheck.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  @DisplayName("未知工具名/未知server通配/不存在Agent_各产生WARN_不抛")
  void unknownTargets_warn() {
    rules.add(rule("GLOBAL_DENY", null, "no_such_tool"));
    rules.add(rule("GLOBAL_DENY", null, "ghost-mcp:*"));
    rules.add(rule("AGENT_EXEMPT", "no-such-agent", "shell"));

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();

    assertThat(warns()).anyMatch(m -> m.contains("未注册的工具"));
    assertThat(warns()).anyMatch(m -> m.contains("未匹配任何已连接的 MCP server"));
    assertThat(warns()).anyMatch(m -> m.contains("不存在的 Agent"));
  }

  @Test
  @DisplayName("策略致有效集全空_WARN且不抛")
  void emptyEffectiveSet_warns() {
    profileRegistry.register(profile("locked-agent", List.of("shell")));
    rules.add(rule("GLOBAL_DENY", null, "shell"));

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();

    assertThat(warns()).anyMatch(m -> m.contains("locked-agent") && m.contains("有效工具集为空"));
  }

  @Test
  @DisplayName("正常策略_无WARN")
  void healthyPolicy_noWarn() {
    profileRegistry.register(profile("ops-agent", List.of("shell", "read_file")));
    rules.add(rule("GLOBAL_DENY", null, "shell"));
    rules.add(rule("AGENT_EXEMPT", "ops-agent", "shell"));

    check.run(null);

    assertThat(warns()).isEmpty();
  }

  @Test
  @DisplayName("未声明工具的Agent_不因全空告警")
  void agentWithoutTools_noWarn() {
    profileRegistry.register(profile("chat-only", List.of()));

    check.run(null);

    assertThat(warns()).isEmpty();
  }

  private List<String> warns() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static ToolPolicyRule rule(String type, String agent, String pattern) {
    ToolPolicyRule r = new ToolPolicyRule();
    r.setRuleType(type);
    r.setAgentName(agent);
    r.setPattern(pattern);
    return r;
  }

  private static Profile profile(String name, List<String> tools) {
    return new Profile(
        name,
        "d",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(5, 20));
  }

  private static OryxTool stubTool(String name) {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn(name);
    return tool;
  }
}
