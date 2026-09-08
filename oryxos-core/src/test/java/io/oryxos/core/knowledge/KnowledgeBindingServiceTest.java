package io.oryxos.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.testing.SymlinkAssumptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBindingServiceTest {

  @TempDir Path root;

  private KnowledgeBindingService service;

  @BeforeEach
  void setUp() {
    KnowledgeWorkspaceFixture.workspace(root);
    KnowledgeWorkspaceFixture.knowledgeBase(root, "ops", "运维手册", Map.of("a.md", "# 内容"));
    KnowledgeWorkspaceFixture.knowledgeBase(root, "faq", "产品FAQ", Map.of("q.md", "# 问答"));
    KnowledgeWorkspaceFixture.agent(root, "assistant");
    service = new KnowledgeBindingService(root);
  }

  @Test
  void bindCreatesFixedRelativeLinkAndInspectReturnsMetadata() throws IOException {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    BoundKnowledgeDescriptor bound = service.bind("assistant", "ops");

    assertEquals("ops", bound.name());
    assertEquals("运维手册", bound.description());
    Path link = root.resolve("agents/assistant/knowledge/ops");
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(Path.of("..", "..", "..", "knowledge", "ops"), Files.readSymbolicLink(link));

    // 幂等：重复绑定同一有效库不报错
    assertEquals("ops", service.bind("assistant", "ops").name());
  }

  @Test
  void bindRejectsUnknownKnowledgeBaseAndUnknownAgent() {
    assertThrows(IllegalArgumentException.class, () -> service.bind("assistant", "nope"));
    assertThrows(IllegalArgumentException.class, () -> service.bind("ghost", "ops"));
    assertThrows(IllegalArgumentException.class, () -> service.bind("assistant", "../escape"));
  }

  @Test
  void unbindIsIdempotentAndRefusesUncontrolledEntries() throws IOException {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    service.bind("assistant", "ops");
    service.unbind("assistant", "ops");
    service.unbind("assistant", "ops"); // 幂等

    // 绑定位置被普通目录占用：拒绝删除
    Files.createDirectories(root.resolve("agents/assistant/knowledge/ops"));
    assertThrows(IllegalArgumentException.class, () -> service.unbind("assistant", "ops"));
  }

  @Test
  void replaceBindingsSwapsWholeSet() {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    service.bind("assistant", "ops");

    KnowledgeBindingInspection after = service.replaceBindings("assistant", List.of("faq"));

    assertEquals(1, after.bindings().size());
    assertEquals("faq", after.bindings().get(0).name());
    assertTrue(after.issues().isEmpty());
  }

  @Test
  void inspectClassifiesIllegalBindings() throws IOException {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    // 绝对链接 → ESCAPED
    KnowledgeWorkspaceFixture.rawBinding(
        root, "assistant", "abs", root.resolve("knowledge/ops").toAbsolutePath());
    // 名称与目标不一致 → NAME_MISMATCH
    KnowledgeWorkspaceFixture.rawBinding(
        root, "assistant", "wrongname", Path.of("..", "..", "..", "knowledge", "ops"));
    // 目标不存在 → DANGLING
    KnowledgeWorkspaceFixture.rawBinding(
        root, "assistant", "gone", Path.of("..", "..", "..", "knowledge", "gone"));
    // 普通目录混入绑定命名空间 → INVALID_TARGET
    Files.createDirectories(root.resolve("agents/assistant/knowledge/plaindir"));

    KnowledgeBindingInspection inspection = service.inspect("assistant");

    assertTrue(inspection.bindings().isEmpty());
    assertEquals(4, inspection.issues().size());
    assertEquals(
        List.of(
            KnowledgeBindingIssue.Type.ESCAPED,
            KnowledgeBindingIssue.Type.DANGLING,
            KnowledgeBindingIssue.Type.INVALID_TARGET,
            KnowledgeBindingIssue.Type.NAME_MISMATCH),
        inspection.issues().stream().map(KnowledgeBindingIssue::type).toList());
  }

  @Test
  void escapedRealTargetIsRejectedEvenWithLexicalCompliance() throws IOException {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    // knowledge/evil 目录本身是指向库根之外的软连接：词法合规但真实路径越界
    Path outside = Files.createDirectories(root.resolve("outside"));
    Files.writeString(
        outside.resolve(KnowledgeManifest.FILE), "---\nname: evil\ndescription: x\n---\n");
    Files.createSymbolicLink(root.resolve("knowledge/evil"), outside.toAbsolutePath());
    KnowledgeWorkspaceFixture.rawBinding(
        root, "assistant", "evil", Path.of("..", "..", "..", "knowledge", "evil"));

    KnowledgeBindingInspection inspection = service.inspect("assistant");

    assertTrue(inspection.bindings().isEmpty());
    assertEquals(KnowledgeBindingIssue.Type.ESCAPED, inspection.issues().get(0).type());
  }

  @Test
  void referencesAndDeleteProtection() {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    KnowledgeWorkspaceFixture.agent(root, "another");
    service.bind("assistant", "ops");
    service.bind("another", "ops");

    List<KnowledgeReference> refs = service.references("ops");
    assertEquals(2, refs.size());
    assertEquals("another", refs.get(0).agentName());

    KnowledgeReferencedException rejected =
        assertThrows(KnowledgeReferencedException.class, () -> service.ensureDeletable("ops"));
    assertEquals(2, rejected.references().size());

    service.unbind("assistant", "ops");
    service.unbind("another", "ops");
    service.ensureDeletable("ops"); // 不再抛
  }

  @Test
  void reconcileAggregatesIssuesAcrossAgents() {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    KnowledgeWorkspaceFixture.agent(root, "another");
    KnowledgeWorkspaceFixture.rawBinding(
        root, "another", "gone", Path.of("..", "..", "..", "knowledge", "gone"));
    service.bind("assistant", "ops");

    List<KnowledgeBindingIssue> issues = service.reconcile();

    assertEquals(1, issues.size());
    assertEquals("another", issues.get(0).agentName());
    assertEquals(KnowledgeBindingIssue.Type.DANGLING, issues.get(0).type());
  }
}
