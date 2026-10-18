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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

  @Test
  @DisplayName("delete 与 API 同口径：整个目录归档到 <root>/archive/<name>，MEMORY.md 内容原样保留")
  void deleteArchivesAgentAndKeepsMemory() throws Exception {
    Path agent = Files.createDirectories(root.resolve("agents").resolve("ops"));
    Files.writeString(agent.resolve("AGENT.md"), "---\nname: ops\n---\n");
    Files.writeString(agent.resolve("MEMORY.md"), "# 记忆\n\n长期记忆内容。\n");

    ProfileCommand.DeleteCommand delete = new ProfileCommand.DeleteCommand();
    delete.name = "ops";
    delete.versionBump = noopBump();
    delete.run();

    assertFalse(Files.exists(root.resolve("agents").resolve("ops")), "原目录必须消失");
    Path archived = root.resolve("archive").resolve("ops");
    assertTrue(
        Files.isRegularFile(archived.resolve("AGENT.md")), "归档必须与 API 同源：<root>/archive/<name>");
    assertEquals(
        "# 记忆\n\n长期记忆内容。\n", Files.readString(archived.resolve("MEMORY.md")), "长期记忆不得随删除一起丢失");
  }

  @Test
  @DisplayName("归档目标被占用时加时间戳后缀（与 AgentStore.archive 同源），旧归档不得被覆盖")
  void deleteArchiveDoesNotOverwriteExistingArchive() throws Exception {
    Files.createDirectories(root.resolve("archive").resolve("ops"));
    Files.writeString(root.resolve("archive/ops/older.md"), "旧归档");
    Path agent = Files.createDirectories(root.resolve("agents").resolve("ops"));
    Files.writeString(agent.resolve("AGENT.md"), "---\nname: ops\n---\n");

    ProfileCommand.DeleteCommand delete = new ProfileCommand.DeleteCommand();
    delete.name = "ops";
    delete.versionBump = noopBump();
    delete.run();

    assertEquals("旧归档", Files.readString(root.resolve("archive/ops/older.md")), "旧归档不得被覆盖");
    try (var dirs = Files.list(root.resolve("archive"))) {
      Path renamed =
          dirs.filter(p -> p.getFileName().toString().startsWith("ops-"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("重名归档必须加时间戳后缀（AgentStore.uniqueArchivePath）"));
      assertTrue(Files.isRegularFile(renamed.resolve("AGENT.md")), "新归档目录里必须有 Agent 内容");
    }
  }

  @Test
  @DisplayName("create/delete 落盘后递增 workspace_versions 的 agents 版本号（集群档靠它感知）")
  void createAndDeleteBumpAgentsVersion() throws Exception {
    String url = "jdbc:sqlite:" + root.resolve("versions.db");
    seedWorkspaceVersions(url);
    WorkspaceVersionBump dbBump =
        new WorkspaceVersionBump() {
          @Override
          protected Connection connect() throws SQLException {
            return DriverManager.getConnection(url);
          }
        };

    ProfileCommand.CreateCommand create = new ProfileCommand.CreateCommand();
    create.name = "ops";
    create.versionBump = dbBump;
    create.run();
    assertEquals(1, readWorkspaceVersion(url, "agents"), "create 必须 bump agents");

    Files.writeString(root.resolve("agents/ops/MEMORY.md"), "# 记忆\n");
    ProfileCommand.DeleteCommand delete = new ProfileCommand.DeleteCommand();
    delete.name = "ops";
    delete.versionBump = dbBump;
    delete.run();
    assertEquals(2, readWorkspaceVersion(url, "agents"), "delete 必须 bump agents");
    assertEquals(0, readWorkspaceVersion(url, "knowledge"), "只 bump agents 域，不得碰其他域");
  }

  /** 不碰任何库的写端：用例只想验证文件面行为时用，避免依赖进程 CWD 下的真实配置。 */
  private static WorkspaceVersionBump noopBump() {
    return new WorkspaceVersionBump() {
      @Override
      protected Connection connect() {
        return null;
      }
    };
  }

  private static void seedWorkspaceVersions(String url) throws SQLException {
    try (Connection conn = DriverManager.getConnection(url);
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS workspace_versions ("
              + "domain VARCHAR(32) PRIMARY KEY,"
              + " version BIGINT NOT NULL,"
              + " updated_by VARCHAR(128) NOT NULL,"
              + " updated_at TIMESTAMP NOT NULL)");
      stmt.execute(
          "INSERT INTO workspace_versions (domain, version, updated_by, updated_at)"
              + " VALUES ('agents', 0, 'migration', CURRENT_TIMESTAMP)");
      stmt.execute(
          "INSERT INTO workspace_versions (domain, version, updated_by, updated_at)"
              + " VALUES ('knowledge', 0, 'migration', CURRENT_TIMESTAMP)");
    }
  }

  private static long readWorkspaceVersion(String url, String domain) throws SQLException {
    try (Connection conn = DriverManager.getConnection(url);
        PreparedStatement stmt =
            conn.prepareStatement("SELECT version FROM workspace_versions WHERE domain = ?")) {
      stmt.setString(1, domain);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? rs.getLong(1) : -1L;
      }
    }
  }
}
