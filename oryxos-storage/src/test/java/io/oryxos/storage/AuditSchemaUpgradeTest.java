package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class AuditSchemaUpgradeTest {

  @TempDir Path tempDir;

  @Test
  void addsProfileAndCostColumnsToLegacyTablesAndBuildsPricing() throws Exception {
    SQLiteDataSource dataSource = dataSource("legacy.db");
    execute(
        dataSource,
        legacyLlmCalls(),
        legacyToolInvocations(),
        legacyAgentExecutions(),
        legacyLlmCallRow());

    new AuditSchemaUpgrade(dataSource).upgrade();

    try (Connection connection = dataSource.getConnection()) {
      assertThat(columns(connection, "llm_calls"))
          .contains("cost_micros", "profile_name", "trace_id");
      assertThat(columns(connection, "tool_invocations"))
          .contains("profile_name", "blocked_by", "trace_id", "execution_backend", "container_id");
      assertThat(columns(connection, "agent_executions")).contains("trace_id");
    }
    assertThat(indexNames(dataSource, "llm_calls"))
        .contains("idx_llm_calls_profile", "idx_llm_calls_trace");
    assertThat(indexNames(dataSource, "tool_invocations"))
        .contains("idx_tool_invocations_profile", "idx_tool_invocations_trace");
    assertThat(indexNames(dataSource, "agent_executions")).contains("idx_agent_executions_trace");
    assertThat(tableNames(dataSource)).contains("llm_pricing");
  }

  @Test
  void repeatedUpgradeIsIdempotent() throws Exception {
    SQLiteDataSource dataSource = dataSource("repeat.db");
    execute(dataSource, legacyLlmCalls(), legacyToolInvocations());

    AuditSchemaUpgrade upgrade = new AuditSchemaUpgrade(dataSource);
    upgrade.upgrade();
    upgrade.upgrade(); // 第二次必须无副作用

    try (Connection connection = dataSource.getConnection()) {
      assertThat(columns(connection, "llm_calls"))
          .contains("cost_micros", "profile_name", "trace_id");
    }
  }

  @Test
  void freshInstallSkipsAlterAndStillBuildsIndexes() throws Exception {
    // 还原真实启动顺序：新装库由 schema.sql 全量建表（含新列 + llm_pricing），升级器只补建 profile 索引。
    SQLiteDataSource dataSource = dataSource("fresh.db");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      String schema;
      try (var in = AuditSchemaUpgradeTest.class.getResourceAsStream("/schema.sql")) {
        schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      for (String sql : schema.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }

    new AuditSchemaUpgrade(dataSource).upgrade();

    assertThat(indexNames(dataSource, "llm_calls"))
        .contains("idx_llm_calls_profile", "idx_llm_calls_trace");
    assertThat(indexNames(dataSource, "tool_invocations"))
        .contains("idx_tool_invocations_profile", "idx_tool_invocations_trace");
    assertThat(indexNames(dataSource, "agent_executions")).contains("idx_agent_executions_trace");
  }

  private static String legacyLlmCalls() {
    return """
        CREATE TABLE llm_calls (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id VARCHAR(255) NOT NULL,
            provider VARCHAR(64) NOT NULL,
            model VARCHAR(128) NOT NULL,
            prompt_tokens INTEGER,
            completion_tokens INTEGER,
            total_tokens INTEGER,
            success BOOLEAN NOT NULL,
            error_message TEXT,
            duration_ms INTEGER NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """;
  }

  private static String legacyToolInvocations() {
    return """
        CREATE TABLE tool_invocations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id VARCHAR(255) NOT NULL,
            tool_name VARCHAR(128) NOT NULL,
            input_json TEXT,
            result_json TEXT,
            success BOOLEAN NOT NULL,
            error_message TEXT,
            duration_ms INTEGER NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """;
  }

  private static String legacyAgentExecutions() {
    return """
        CREATE TABLE agent_executions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_name VARCHAR(255) NOT NULL,
            source VARCHAR(32) NOT NULL,
            session_id VARCHAR(512),
            started_at TIMESTAMP NOT NULL,
            ended_at TIMESTAMP,
            success BOOLEAN,
            error_message TEXT,
            duration_ms INTEGER
        )
        """;
  }

  private static String legacyLlmCallRow() {
    return "INSERT INTO llm_calls (session_id, provider, model, success, duration_ms, created_at)"
        + " VALUES ('s-1', 'deepseek', 'deepseek-chat', 1, 100, '2026-08-01T00:00:00Z')";
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

  private static Set<String> columns(Connection connection, String table) throws Exception {
    Set<String> columns = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    return columns;
  }

  private static Set<String> indexNames(SQLiteDataSource dataSource, String table)
      throws Exception {
    Set<String> names = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return names;
  }

  private static Set<String> tableNames(SQLiteDataSource dataSource) throws Exception {
    Set<String> names = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='llm_pricing'")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return names;
  }
}
