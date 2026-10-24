package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** InitCommand 单元：工作区骨架创建，以及"已有文件一律不覆盖"的幂等契约。 */
class InitCommandTest {

  private static final List<String> DIRS =
      List.of("agents", "skills", "knowledge", "output", "memory", "sessions", "logs");
  private static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");

  @TempDir Path tempDir;

  private String previousRoot;

  @BeforeEach
  void pointWorkspaceAtTempDir() {
    previousRoot = System.getProperty("oryxos.root");
    System.setProperty("oryxos.root", tempDir.resolve("workspace").toString());
  }

  @AfterEach
  void restoreWorkspaceRoot() {
    if (previousRoot == null) {
      System.clearProperty("oryxos.root");
    } else {
      System.setProperty("oryxos.root", previousRoot);
    }
  }

  @Test
  @DisplayName("首次 init 创建工作区目录与引导文件")
  void createsSkeletonOnFirstRun() {
    new InitCommand().run();

    Path root = tempDir.resolve("workspace");
    for (String dir : DIRS) {
      assertTrue(Files.isDirectory(root.resolve(dir)), "缺少目录 " + dir);
    }
    for (String file : BOOTSTRAP_FILES) {
      assertTrue(Files.isRegularFile(root.resolve(file)), "缺少引导文件 " + file);
    }
  }

  @Test
  @DisplayName("init 不覆盖已存在的引导文件")
  void keepsExistingBootstrapFiles() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("workspace"));
    Path agents = root.resolve("AGENTS.md");
    Files.writeString(agents, "# 我自己的 Agent 说明\n");

    new InitCommand().run();

    assertEquals("# 我自己的 Agent 说明\n", Files.readString(agents));
    assertTrue(Files.isRegularFile(root.resolve("SOUL.md")));
  }

  @Test
  @DisplayName("重复执行 init 幂等且不抛异常")
  void isIdempotent() throws IOException {
    assertDoesNotThrow(() -> new InitCommand().run());
    Path user = tempDir.resolve("workspace").resolve("USER.md");
    String first = Files.readString(user);

    assertDoesNotThrow(() -> new InitCommand().run());

    assertEquals(first, Files.readString(user));
  }
}
