package io.oryxos.core.workspace.versioned;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.io.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionedAssetSourceTest {

  @TempDir Path root;

  private ClusterProperties cluster;
  private InMemoryVersionedAssetPointerStore pointers;
  private VersionedAssetSource source;
  private final AtomicInteger bumps = new AtomicInteger();

  @BeforeEach
  void setUp() {
    cluster = new ClusterProperties();
    cluster.setVersionedAssetSourceEnabled(true);
    pointers = new InMemoryVersionedAssetPointerStore();
    source = new VersionedAssetSource(root, pointers, cluster, domain -> bumps.incrementAndGet());
  }

  @Test
  void disabledRefusesPublish() {
    cluster.setVersionedAssetSourceEnabled(false);
    assertThrows(
        IllegalStateException.class,
        () -> source.publish(VersionedAssetKind.AGENTS, "demo", "tester"));
  }

  @Test
  void dualReadersSeeSameActiveVersionAfterActivate() throws Exception {
    writeLive(VersionedAssetKind.AGENTS, "demo", "v1-body");
    AssetVersion first = source.publish(VersionedAssetKind.AGENTS, "demo", "a");
    writeLive(VersionedAssetKind.AGENTS, "demo", "v2-body");
    AssetVersion second = source.publish(VersionedAssetKind.AGENTS, "demo", "a");

    VersionedAssetSource readerB =
        new VersionedAssetSource(root, pointers, cluster, WorkspaceVersionNotifierNoop.INSTANCE);
    assertEquals(
        first.version(), readerB.activeVersion(VersionedAssetKind.AGENTS, "demo").getAsLong());

    source.activate(VersionedAssetKind.AGENTS, "demo", second.version(), "a");
    assertEquals(
        second.version(), source.activeVersion(VersionedAssetKind.AGENTS, "demo").getAsLong());
    assertEquals(
        second.version(), readerB.activeVersion(VersionedAssetKind.AGENTS, "demo").getAsLong());
    assertEquals("v2-body", Files.readString(root.resolve("agents/demo/AGENT.md")).strip());
    assertTrue(bumps.get() >= 1);
  }

  @Test
  void activateAndRollbackRestorePriorContent() throws Exception {
    writeLive(VersionedAssetKind.SKILLS, "tool", "skill-one");
    AssetVersion v1 = source.publish(VersionedAssetKind.SKILLS, "tool", "ops");
    writeLive(VersionedAssetKind.SKILLS, "tool", "skill-two");
    AssetVersion v2 = source.publish(VersionedAssetKind.SKILLS, "tool", "ops");
    source.activate(VersionedAssetKind.SKILLS, "tool", v2.version(), "ops");
    assertEquals("skill-two", Files.readString(root.resolve("skills/tool/SKILL.md")).strip());

    AssetVersion rolled = source.rollback(VersionedAssetKind.SKILLS, "tool", "ops");
    assertEquals(v1.version(), rolled.version());
    assertEquals("skill-one", Files.readString(root.resolve("skills/tool/SKILL.md")).strip());
    assertEquals(v1.version(), source.activeVersion(VersionedAssetKind.SKILLS, "tool").getAsLong());
  }

  @Test
  void knowledgeKindPublishActivate() throws Exception {
    Path live = root.resolve("knowledge/kb1");
    Files.createDirectories(live);
    AtomicFiles.writeString(live.resolve("doc.md"), "alpha\n");
    AssetVersion v1 = source.publish(VersionedAssetKind.KNOWLEDGE, "kb1", "ops");
    AtomicFiles.writeString(live.resolve("doc.md"), "beta\n");
    source.publish(VersionedAssetKind.KNOWLEDGE, "kb1", "ops");
    source.activate(VersionedAssetKind.KNOWLEDGE, "kb1", v1.version(), "ops");
    assertEquals("alpha", Files.readString(live.resolve("doc.md")).strip());
  }

  @Test
  void rollbackWithoutPreviousFails() throws Exception {
    writeLive(VersionedAssetKind.AGENTS, "only", "body");
    source.publish(VersionedAssetKind.AGENTS, "only", "a");
    assertFalse(pointers.previousVersion(VersionedAssetKind.AGENTS, "only").isPresent());
    assertThrows(
        IllegalStateException.class, () -> source.rollback(VersionedAssetKind.AGENTS, "only", "a"));
  }

  @Test
  void listVersionsNewestFirst() throws Exception {
    writeLive(VersionedAssetKind.AGENTS, "x", "a");
    source.publish(VersionedAssetKind.AGENTS, "x", "t");
    writeLive(VersionedAssetKind.AGENTS, "x", "b");
    source.publish(VersionedAssetKind.AGENTS, "x", "t");
    List<Long> versions = new ArrayList<>();
    for (AssetVersion v : source.list(VersionedAssetKind.AGENTS, "x")) {
      versions.add(v.version());
    }
    assertEquals(List.of(2L, 1L), versions);
  }

  private void writeLive(VersionedAssetKind kind, String id, String body) throws Exception {
    String file =
        kind == VersionedAssetKind.SKILLS
            ? "SKILL.md"
            : kind == VersionedAssetKind.AGENTS ? "AGENT.md" : "doc.md";
    Path dir = root.resolve(kind.directory()).resolve(id);
    Files.createDirectories(dir);
    AtomicFiles.writeString(dir.resolve(file), body + "\n");
  }

  private enum WorkspaceVersionNotifierNoop
      implements io.oryxos.core.cluster.WorkspaceVersionNotifier {
    INSTANCE;

    @Override
    public void bump(String domain) {}
  }
}
