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

class TeamsParentMigrationTest {

  @TempDir Path tempDir;

  @Test
  void addsParentTeamIdIdempotently() throws Exception {
    SQLiteDataSource dataSource = dataSource("legacy-team-parent.db");
    execute(
        dataSource,
        "CREATE TABLE teams ("
            + "team_id VARCHAR(128) PRIMARY KEY,"
            + "display_name VARCHAR(255) NOT NULL,"
            + "org_id VARCHAR(128),"
            + "created_at TIMESTAMP NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL)",
        "INSERT INTO teams (team_id, display_name, created_at, updated_at)"
            + " VALUES ('eng', 'Engineering', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z')");

    migrate(dataSource);
    migrate(dataSource);

    assertThat(columns(dataSource, "teams"))
        .containsExactlyInAnyOrder(
            "team_id", "display_name", "org_id", "created_at", "updated_at", "parent_team_id");
  }

  private SQLiteDataSource dataSource(String name) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(name));
    return dataSource;
  }

  private static void migrate(SQLiteDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      new TeamsParentMigration().migrate(connection);
    }
  }

  private static void execute(SQLiteDataSource dataSource, String... sqls) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : sqls) {
        statement.execute(sql);
      }
    }
  }

  private static Set<String> columns(SQLiteDataSource dataSource, String table) throws Exception {
    Set<String> names = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return names;
  }
}
