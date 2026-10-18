package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code oryxos session list} 的退出码：查询失败必须非零（脚本的 {@code || echo failed} 才会触发）， 「库还没建 /
 * 空表」是「还没有会话」而非错误，保持 0。
 */
class SessionListCommandTest {

  /** 与 db/migration/sqlite/V1__baseline.sql 的 sessions 表同构（只列本命令用到的列）。 */
  private static final String DDL =
      """
      CREATE TABLE IF NOT EXISTS sessions (
          session_id VARCHAR(512) PRIMARY KEY,
          profile_name VARCHAR(255) NOT NULL,
          channel VARCHAR(64) NOT NULL,
          user_id VARCHAR(255) NOT NULL,
          messages_json TEXT,
          status VARCHAR(32) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          last_active_at TIMESTAMP,
          archived_at TIMESTAMP
      )
      """;

  @TempDir Path tmp;

  @Test
  @DisplayName("库文件尚不存在：还没有会话，不是失败，退出 0")
  void missingDatabaseIsNotAFailure() {
    Result result = run(tmp.resolve("absent.db"));

    assertEquals(0, result.code(), result.err());
    assertTrue(result.out().contains("暂无会话（"), result.out());
    assertTrue(result.err().isEmpty(), "这条路不该有错误输出: " + result.err());
  }

  @Test
  @DisplayName("查询成功：列出会话并退出 0")
  void listsSessionsAndExitsZero() throws Exception {
    Path dbFile = tmp.resolve("oryxos.db");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        Statement stmt = conn.createStatement()) {
      stmt.execute(DDL);
      stmt.execute(
          "INSERT INTO sessions"
              + " (session_id, profile_name, channel, user_id, status, created_at, last_active_at)"
              + " VALUES ('sess-1', 'ops', 'cli', 'u1', 'ACTIVE', CURRENT_TIMESTAMP,"
              + " CURRENT_TIMESTAMP)");
    }

    Result result = run(dbFile);

    assertEquals(0, result.code(), result.err());
    assertTrue(result.out().contains("sess-1"), result.out());
    assertTrue(result.out().contains("ops"), result.out());
  }

  @Test
  @DisplayName("空表：暂无会话，退出 0")
  void emptyTableExitsZero() throws Exception {
    Path dbFile = tmp.resolve("empty.db");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        Statement stmt = conn.createStatement()) {
      stmt.execute(DDL);
    }

    Result result = run(dbFile);

    assertEquals(0, result.code(), result.err());
    assertTrue(result.out().contains("暂无会话。"), result.out());
  }

  @Test
  @DisplayName("查询失败：非零退出且 stderr 有信息")
  void queryFailureExitsNonZero() throws Exception {
    Path broken = tmp.resolve("broken.db");
    Files.writeString(broken, "this is not a sqlite database");

    Result result = run(broken);

    assertEquals(1, result.code(), "查询失败必须非零退出");
    assertTrue(result.err().contains("查询会话失败"), result.err());
  }

  private Result run(Path dbFile) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
    PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
    int code =
        new SessionListCommand.ListCommand(
                LightDbConfig.forUrl("jdbc:sqlite:" + dbFile), outStream, errStream)
            .call();
    outStream.flush();
    errStream.flush();
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private record Result(int code, String out, String err) {}
}
