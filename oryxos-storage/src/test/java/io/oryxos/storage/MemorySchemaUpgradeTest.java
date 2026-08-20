package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class MemorySchemaUpgradeTest {

  @TempDir Path tempDir;

  @Test
  void addsAgentColumnToLegacyTableAndBackfillsGlobal() throws Exception {
    SQLiteDataSource dataSource = dataSource("legacy.db");
    execute(
        dataSource,
        """
        CREATE TABLE memory_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scope VARCHAR(16) NOT NULL,
            content TEXT NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        "INSERT INTO memory_entries (scope, content, created_at)"
            + " VALUES ('ARCHIVAL', '存量记忆一条', '2026-08-01T00:00:00Z')");

    new MemorySchemaUpgrade(dataSource).upgrade();

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT agent_name, content FROM memory_entries")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("agent_name")).isEqualTo(MemoryEntry.GLOBAL_AGENT);
      assertThat(rows.getString("content")).isEqualTo("存量记忆一条");
      assertThat(rows.next()).isFalse();
    }
    assertThat(indexNames(dataSource)).contains("idx_memory_agent");
  }

  @Test
  void repeatedUpgradeIsIdempotent() throws Exception {
    SQLiteDataSource dataSource = dataSource("repeat.db");
    execute(
        dataSource,
        """
        CREATE TABLE memory_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scope VARCHAR(16) NOT NULL,
            content TEXT NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """);

    MemorySchemaUpgrade upgrade = new MemorySchemaUpgrade(dataSource);
    upgrade.upgrade();
    upgrade.upgrade(); // 第二次必须无副作用地通过

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(memory_entries)")) {
      Set<String> columns = new HashSet<>();
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
      assertThat(columns)
          .containsExactlyInAnyOrder("id", "agent_name", "scope", "content", "created_at");
    }
  }

  @Test
  void legacyDatabaseSurvivesRealStartupOrder() throws Exception {
    // 还原真实启动顺序：存量旧结构库 → 全量 schema.sql（Spring 脚本初始化）→ MemorySchemaUpgrade。
    // 回归背景：idx_memory_agent 曾写在 schema.sql 里，在补列之前执行直接炸掉整个启动（SC-003）。
    SQLiteDataSource dataSource = dataSource("startup-order.db");
    execute(
        dataSource,
        """
        CREATE TABLE memory_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scope VARCHAR(16) NOT NULL,
            content TEXT NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        "INSERT INTO memory_entries (scope, content, created_at)"
            + " VALUES ('ARCHIVAL', '存量条目', '2026-08-01T00:00:00Z')");

    String schema;
    try (var in = MemorySchemaUpgradeTest.class.getResourceAsStream("/schema.sql")) {
      schema = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : schema.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }

    new MemorySchemaUpgrade(dataSource).upgrade();

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT agent_name FROM memory_entries")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("agent_name")).isEqualTo(MemoryEntry.GLOBAL_AGENT);
    }
    assertThat(indexNames(dataSource)).contains("idx_memory_agent");
  }

  @Test
  void missingTableIsSkipped() throws Exception {
    SQLiteDataSource dataSource = dataSource("fresh.db");

    new MemorySchemaUpgrade(dataSource).upgrade(); // 新装库：schema.sql 负责全量建表，升级器不动

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='memory_entries'")) {
      assertThat(rows.next()).isFalse();
    }
  }

  private SQLiteDataSource dataSource(String fileName) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(fileName));
    return dataSource;
  }

  private static void execute(SQLiteDataSource dataSource, String... statements) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static Set<String> indexNames(SQLiteDataSource dataSource) throws Exception {
    Set<String> names = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA index_list(memory_entries)")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return names;
  }
}
