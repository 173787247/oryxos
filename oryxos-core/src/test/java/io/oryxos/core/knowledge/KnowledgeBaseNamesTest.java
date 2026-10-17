package io.oryxos.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.testing.SymlinkAssumptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 知识库名规则本身：字符集一臂挡 {@code .}/{@code ..}/多段名，真实路径一臂挡「库目录本身是指向根外的软链」。 六入口的行为用例在 {@code
 * oryxos-knowledge} 与 {@code oryxos-web} 侧。
 */
class KnowledgeBaseNamesTest {

  @TempDir Path workspace;

  private Path knowledgeRoot;

  @BeforeEach
  void setUp() throws IOException {
    knowledgeRoot = Files.createDirectories(workspace.resolve("knowledge"));
  }

  @Test
  @DisplayName("字符集：只接受字母/数字/下划线/连字符")
  void acceptsOnlySafeCharacterSet() {
    for (String name : List.of("ops", "ops-manual", "kb_1", "A-Z_09", "-", "_")) {
      assertTrue(KnowledgeBaseNames.isSafe(name), "应接受: " + name);
      KnowledgeBaseNames.requireBaseDir(knowledgeRoot, name);
    }
    for (String name :
        List.of(
            "",
            " ",
            "ops ",
            " ops",
            ".",
            "..",
            "../agents/demo",
            "a/b",
            "a\\b",
            "团队知识",
            "my kb",
            "v1.2")) {
      assertTrue(!KnowledgeBaseNames.isSafe(name), "应拒绝: " + name);
      IllegalArgumentException rejected =
          assertThrows(
              IllegalArgumentException.class,
              () -> KnowledgeBaseNames.requireBaseDir(knowledgeRoot, name));
      assertTrue(rejected.getMessage().contains("非法知识库名"), "拒绝原因必须点名规则: " + rejected.getMessage());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> KnowledgeBaseNames.requireBaseDir(knowledgeRoot, null));
  }

  @Test
  @DisplayName("合法名解析到知识库根之下的库目录（目录不必已存在）")
  void resolvesUnderKnowledgeRoot() throws IOException {
    Path existing = Files.createDirectories(knowledgeRoot.resolve("ops"));
    assertEquals(existing.toRealPath(), KnowledgeBaseNames.requireBaseDir(knowledgeRoot, "ops"));
    Path planned = KnowledgeBaseNames.requireBaseDir(knowledgeRoot, "faq");
    assertTrue(planned.startsWith(knowledgeRoot.toRealPath()), "尚未创建的库目录也必须落在根内: " + planned);
  }

  @Test
  @DisplayName("库目录本身是指向根外的软链：字符集过关也拒绝")
  void rejectsSymlinkedBaseDirectoryPointingOutside() throws IOException {
    Path outside = Files.createDirectories(workspace.resolve("outside-kb"));
    SymlinkAssumptions.createSymbolicLinkOrAssume(
        knowledgeRoot.resolve("escape"), Path.of("..", "outside-kb"));

    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class,
            () -> KnowledgeBaseNames.requireBaseDir(knowledgeRoot, "escape"));
    assertTrue(rejected.getMessage().contains("越界"), "必须按边界臂拒绝: " + rejected.getMessage());
    assertTrue(Files.isDirectory(outside), "根外目标不得被改动");
  }

  @Test
  @DisplayName("悬空软链：解析失败即拒绝（失败关闭）")
  void rejectsDanglingSymlink() throws IOException {
    SymlinkAssumptions.createSymbolicLinkOrAssume(
        knowledgeRoot.resolve("ghost"), Path.of("..", "missing-kb"));

    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class,
            () -> KnowledgeBaseNames.requireBaseDir(knowledgeRoot, "ghost"));
    assertTrue(rejected.getMessage().contains("越界"), "必须失败关闭: " + rejected.getMessage());
  }
}
