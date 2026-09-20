package io.oryxos.core.workspace;

import static org.junit.jupiter.api.Assertions.*;

import io.oryxos.core.agent.AgentStore;
import io.oryxos.core.skill.SkillStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceBusinessPluginTest {
  @TempDir Path root;

  @Test
  void selectedPluginControlsRealAgentAndSkillOperations() throws Exception {
    AtomicInteger operations = new AtomicInteger();
    WorkspaceFileSystem fileSystem = new WorkspaceFileSystem(root, operations::incrementAndGet);
    WorkspaceStorage alternative =
        new WorkspaceStorage() {
          public String providerId() {
            return "alternative";
          }

          public Path root() {
            return fileSystem.wrap(root);
          }

          public Set<WorkspaceCapability> capabilities() {
            return Set.of(WorkspaceCapability.values());
          }

          public void checkHealth() {}

          public Path resolve(String path) {
            return root().resolve(path);
          }

          public Path nativePath(Path path) {
            return fileSystem.unwrap(path);
          }
        };
    WorkspaceStorageProvider plugin =
        new WorkspaceStorageProvider() {
          public String id() {
            return "alternative";
          }

          public WorkspaceStorage open(Path ignored, String identity) {
            return alternative;
          }
        };
    WorkspaceStorage selected =
        new WorkspaceStorageRegistry(List.of(plugin)).open("alternative", root, "");
    AgentStore agents = new AgentStore(selected.root());
    agents.write("demo", "agent content");
    int writes = operations.get();
    assertTrue(writes > 0);
    assertEquals("agent content", agents.read("demo"));
    assertTrue(operations.get() > writes, "Agent reads must dispatch through selected filesystem");
    SkillStore skills = new SkillStore(selected.root());
    int beforeSkill = operations.get();
    skills.write("example", "skill content");
    assertTrue(
        operations.get() > beforeSkill, "Skill writes must dispatch through selected filesystem");
    assertEquals(
        "skill content", Files.readString(selected.root().resolve("skills/example/SKILL.md")));
    assertSame(selected.root().getFileSystem(), agents.agentsDir().getFileSystem());
  }
}
