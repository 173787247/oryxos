package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentYamlImplicitNameTest {

  @TempDir Path root;

  @Test
  @DisplayName("create_名yes不能被YAML收成true")
  void create_yesRemainsYes() throws Exception {
    Files.createDirectories(root.resolve("agents"));
    ProfileRegistry profiles = new ProfileRegistry();
    AgentLoader loader = new AgentLoader(root.resolve("agents"), Set.of("mock"));
    AgentLifecycleService service =
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

    Profile created = service.create("yes", "布尔词名");

    assertEquals("yes", created.name());
    assertTrue(profiles.get("yes").isPresent());
    assertTrue(profiles.get("true").isEmpty());
    assertTrue(Files.isDirectory(root.resolve("agents/yes")));
    Profile reloaded = loader.deriveProfile(root.resolve("agents/yes"));
    assertEquals("yes", reloaded.name());
  }
}
