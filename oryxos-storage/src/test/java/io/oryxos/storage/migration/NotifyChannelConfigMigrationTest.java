package io.oryxos.storage.migration;

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

class NotifyChannelConfigMigrationTest {

  @TempDir Path tempDir;

  @Test
  void addsConfigColumnToLegacyTableAndIsIdempotent() throws Exception {
    SQLiteDataSource dataSource = dataSource("legacy.db");
    execute(
        dataSource,
        """
        CREATE TABLE notify_channels (
            name VARCHAR(128) PRIMARY KEY,
            type VARCHAR(32) NOT NULL,
            url TEXT NOT NULL,
            description TEXT,
            created_at TIMESTAMP NOT NULL,
            updated_at TIMESTAMP NOT NULL
        )
        """,
        "INSERT INTO notify_channels (name, type, url, created_at, updated_at)"
            + " VALUES ('ops', 'webhook', 'https://example.com/hook',"
            + " '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z')");

    migrate(dataSource);
    migrate(dataSource); // 第二次必须无副作用

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT name, config FROM notify_channels")) {
      assertThat(columns(dataSource)).contains("config");
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("name")).isEqualTo("ops");
      assertThat(rows.getString("config")).isNull();
    }
  }

  @Test
  void missingTableIsSkipped() throws Exception {
    SQLiteDataSource dataSource = dataSource("fresh.db");

    migrate(dataSource); // 表不存在的防御分支：正常序列 V1 必先建表

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='notify_channels'")) {
      assertThat(rows.next()).isFalse();
    }
  }

  private static void migrate(SQLiteDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      new NotifyChannelConfigMigration().migrate(connection);
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

  private static Set<String> columns(SQLiteDataSource dataSource) throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(notify_channels)")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    return columns;
  }
}
