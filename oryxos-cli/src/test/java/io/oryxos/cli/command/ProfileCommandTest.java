package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsCli;
import io.oryxos.core.agent.AgentLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ProfileCommandTest {

  /** 目录布局：sandbox/workspace 是工作区根（默认部署里就是 .oryxos），sandbox 是它的父目录。 */
  @TempDir Path sandbox;

  private Path root;

  @BeforeEach
  void setRoot() throws Exception {
    root = Files.createDirectories(sandbox.resolve("workspace"));
    System.setProperty("oryxos.root", root.toString());
    Files.createDirectories(root.resolve("agents"));
  }

  @AfterEach
  void clearRoot() {
    System.clearProperty("oryxos.root");
  }

  @Test
  void showRejectsOversizedAgentMarkdown() throws Exception {
    Path agentDir = Files.createDirectories(root.resolve("agents").resolve("huge"));
    Path markdown = agentDir.resolve("AGENT.md");
    long over = AgentLoader.MAX_AGENT_MD_BYTES + 1;
    try (var out = Files.newOutputStream(markdown)) {
      byte[] chunk = new byte[1024 * 1024];
      Arrays.fill(chunk, (byte) 'x');
      long left = over;
      while (left > 0) {
        int n = (int) Math.min(left, chunk.length);
        out.write(chunk, 0, n);
        left -= n;
      }
    }

    ProfileCommand.ShowCommand show = new ProfileCommand.ShowCommand();
    show.name = "huge";
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, show::run);
    assertTrue(ex.getMessage().contains("10 MiB"));
  }

  @Test
  @DisplayName("delete .. / ../.. / 绝对路径：一律拒绝，且工作区、父目录与文件一个不少")
  void deleteRejectsTraversalAndDeletesNothing() throws Exception {
    Path db = writeFile(root.resolve("oryxos.db"), "db");
    Path memory = writeFile(root.resolve("memory").resolve("keep.txt"), "keep");
    Path agentMd = writeFile(root.resolve("agents").resolve("legit").resolve("AGENT.md"), "legit");
    Path parentMarker = writeFile(sandbox.resolve("project-root.txt"), "marker");
    Path absoluteVictim = Files.createDirectories(sandbox.resolve("absolute-victim"));
    Path absoluteMarker = writeFile(absoluteVictim.resolve("keep.txt"), "keep");

    for (String name : List.of("..", "../..", absoluteVictim.toString())) {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class, () -> delete(name), "delete " + name + " 必须被拒");
      assertTrue(
          ex.getMessage().contains("非法 Agent 名"), "错误信息要点名规则（" + name + "）: " + ex.getMessage());
    }

    // 拒绝之后工作区根、父目录与其中的文件都必须原样存在——删除真的发生过的话这里会红
    assertTrue(Files.isDirectory(root), "工作区根被删了");
    assertTrue(Files.isDirectory(sandbox), "工作区父目录被删了");
    assertTrue(Files.isRegularFile(db), "oryxos.db 被删了");
    assertTrue(Files.isRegularFile(memory), "memory/ 被删了");
    assertTrue(Files.isRegularFile(agentMd), "agents/ 被删了");
    assertTrue(Files.isRegularFile(parentMarker), "工作区父目录里的文件被删了");
    assertTrue(Files.isRegularFile(absoluteMarker), "绝对路径目标被删了");
  }

  @Test
  @DisplayName("create / show ../../x：拒绝且不创建任何目录")
  void createAndShowRejectTraversal() {
    for (String name : List.of("../x", "../../x", sandbox.resolve("elsewhere").toString())) {
      IllegalArgumentException created =
          assertThrows(
              IllegalArgumentException.class,
              () -> {
                ProfileCommand.CreateCommand create = new ProfileCommand.CreateCommand();
                create.name = name;
                create.run();
              },
              "create " + name + " 必须被拒");
      assertTrue(created.getMessage().contains("非法 Agent 名"), created.getMessage());

      IllegalArgumentException shown =
          assertThrows(
              IllegalArgumentException.class,
              () -> {
                ProfileCommand.ShowCommand show = new ProfileCommand.ShowCommand();
                show.name = name;
                show.run();
              },
              "show " + name + " 必须被拒");
      assertTrue(shown.getMessage().contains("非法 Agent 名"), shown.getMessage());
    }

    assertFalse(Files.exists(root.resolve("x")), "create ../x 不应在工作区根创建目录");
    assertFalse(Files.exists(sandbox.resolve("x")), "create ../../x 不应在父目录创建目录");
    assertFalse(Files.exists(sandbox.resolve("elsewhere")), "create 绝对路径不应创建目录");
  }

  @Test
  @DisplayName("agents 内软链指向根外：show / create 被真实路径边界拒绝，根外文件不受影响")
  void rejectsSymlinkEscapingAgentsRoot() throws Exception {
    Path outside = Files.createDirectories(sandbox.resolve("outside-agent"));
    Path outsideMarkdown = writeFile(outside.resolve("AGENT.md"), "OUTSIDE");
    Files.createSymbolicLink(root.resolve("agents").resolve("escape"), outside);

    ProfileCommand.ShowCommand show = new ProfileCommand.ShowCommand();
    show.name = "escape";
    IllegalArgumentException shown = assertThrows(IllegalArgumentException.class, show::run);
    assertTrue(shown.getMessage().contains("越界"), shown.getMessage());

    ProfileCommand.CreateCommand create = new ProfileCommand.CreateCommand();
    create.name = "escape";
    IllegalArgumentException created = assertThrows(IllegalArgumentException.class, create::run);
    assertTrue(created.getMessage().contains("越界"), created.getMessage());

    assertEquals("OUTSIDE", Files.readString(outsideMarkdown), "根外文件不得被覆盖");
  }

  @Test
  @DisplayName("合法名字照常：create 落 AGENT.md，delete 删掉整个目录")
  void validNameCreatesAndDeletes() throws Exception {
    ProfileCommand.CreateCommand create = new ProfileCommand.CreateCommand();
    create.name = "ops_1";
    create.run();

    Path dir = root.resolve("agents").resolve("ops_1");
    Path markdown = dir.resolve("AGENT.md");
    assertTrue(Files.isRegularFile(markdown), "create 应写出 AGENT.md");
    assertTrue(Files.readString(markdown).contains("name: ops_1"));

    ProfileCommand.DeleteCommand delete = new ProfileCommand.DeleteCommand();
    delete.name = "ops_1";
    delete.run();

    assertFalse(Files.exists(dir), "delete 应删掉 Agent 目录");
  }

  @Test
  @DisplayName("CLI：非法名字以退出码 1 结束，而不是打印一行后 return 0")
  void cliExitsNonZeroOnTraversal() throws Exception {
    Path db = writeFile(root.resolve("oryxos.db"), "db");

    int exitCode = new CommandLine(new OryxOsCli()).execute("profile", "delete", "..");

    assertEquals(1, exitCode, "非法名字必须让进程退出码非 0（脚本 || echo failed 才会触发）");
    assertTrue(Files.isDirectory(root.resolve("agents")), "工作区 agents/ 被删了");
    assertTrue(Files.isRegularFile(db), "工作区文件被删了");
  }

  private static void delete(String name) {
    ProfileCommand.DeleteCommand delete = new ProfileCommand.DeleteCommand();
    delete.name = name;
    delete.run();
  }

  private static Path writeFile(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path;
  }
}
