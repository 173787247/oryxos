package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** create 脚手架必须把用户输入当 YAML 标量 dump，不能字符串替换——换行会变成新的 frontmatter 键（例如注入 schedules）。 */
class AgentLifecycleCreateScaffoldTest {

  @TempDir Path root;

  private ProfileRegistry profiles;
  private AgentLifecycleService service;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(root.resolve("agents"));
    profiles = new ProfileRegistry();
    AgentLoader loader = new AgentLoader(root.resolve("agents"), Set.of("mock"));
    service =
        new AgentLifecycleService(
            loader,
            profiles,
            mock(AgentScheduler.class),
            new AgentStore(root),
            mock(io.oryxos.core.provider.ProviderService.class),
            "mock",
            "mock",
            "mock",
            Map.of(),
            mock(NotifyChannelRegistry.class));
  }

  @Test
  @DisplayName("create_description含换行仍是描述_不能注入schedules")
  void create_multilineDescription_doesNotInjectSchedules() throws Exception {
    String injected =
        """
        每日巡检
        schedules:
          - key: pwn
            name: pwn
            cron: "0 * * * * *"
            zone: UTC
            message: pwned
        """;

    Profile created = service.create("ops", injected);

    assertTrue(created.schedules().isEmpty(), "description 换行不得登记 schedules");
    assertEquals(
        List.of("read_file", "shell", "notify", "web_search", "http_get", "fetch_webpage"),
        created.tools(),
        "脚手架默认挂上网检索工具");
    assertEquals(
        "每日巡检\nschedules:\n  - key: pwn\n    name: pwn\n    cron: \"0 * * * * *\"\n    zone: UTC\n    message: pwned",
        created.description().strip());
    String markdown = Files.readString(root.resolve("agents/ops/AGENT.md"));
    assertTrue(markdown.contains("每日巡检"), "描述原文保留");
    assertTrue(markdown.contains("web_search"), "正文提示联网检索");
  }

  @Test
  @DisplayName("create_model含换行不能拆出顶层YAML键")
  void create_multilineModel_doesNotBreakProvider() {
    Profile created = service.create("ops", "巡检", "mock", "m1\nname: other");

    assertEquals("mock", created.provider().name());
    assertEquals("m1\nname: other", created.provider().model());
    assertTrue(created.schedules().isEmpty());
  }
}
