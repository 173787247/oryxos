package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 025 存量接管集成测试（原 ScheduleSchemaUpgradeIntegrationTest 扩展）：任意历史状态的存量 SQLite 库 零配置启动即被 Flyway 幂等序列
 * V1~V5 收敛——legacy 调度表重建、审计/记忆/通知渠道补列、数据完好； 二次重启迁移历史无新增（幂等接管，SC-001/FR-004）。
 */
class LegacyTakeoverIT {

  @Test
  @DisplayName("startup converges a legacy database via Flyway without losing rows")
  void startupConvergesLegacySchemaAndPreservesRows() throws Exception {
    Path root = seedWorkspace();
    Path database = root.resolve("legacy-takeover.db");
    String dbUrl = "jdbc:sqlite:" + database;
    createLegacyDatabase(dbUrl);

    try (ConfigurableApplicationContext context = boot(root, dbUrl)) {
      // The scheduler is an eager dependency of the normal web runtime; resolve it here because
      // this fixture deliberately uses WebApplicationType.NONE to avoid binding a server port.
      assertNotNull(context.getBean(AgentScheduler.class));
      assertEquals(dbUrl, jdbcUrl(context.getBean(DataSource.class)));
      assertNewSchemaAndPreservedRows(dbUrl);
    }
    long historyRows = flywayHistoryRows(dbUrl);
    assertTrue(historyRows >= 5, "expected baseline + V1~V5 in history, got " + historyRows);

    // 二次重启：不重复执行接管动作，迁移历史无新增（SC-001）
    try (ConfigurableApplicationContext context = boot(root, dbUrl)) {
      assertNotNull(context.getBean(AgentScheduler.class));
    }
    assertEquals(historyRows, flywayHistoryRows(dbUrl));
    assertNewSchemaAndPreservedRows(dbUrl);
  }

  private static String jdbcUrl(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      return connection.getMetaData().getURL();
    }
  }

  private static ConfigurableApplicationContext boot(Path root, String dbUrl) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--spring.datasource.url=" + dbUrl,
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  /** 存量库 fixture：legacy 调度表 + 缺 trace/profile/cost 列的审计表 + 缺 agent_name 的记忆表 + 缺 config 的渠道表。 */
  private static void createLegacyDatabase(String dbUrl) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE scheduled_tasks (
            task_id VARCHAR(255) PRIMARY KEY,
            profile_name VARCHAR(255) NOT NULL,
            cron VARCHAR(128) NOT NULL,
            zone VARCHAR(64),
            message TEXT,
            enabled BOOLEAN NOT NULL DEFAULT 1,
            next_run_at TIMESTAMP,
            last_run_at TIMESTAMP,
            last_status VARCHAR(16),
            run_count INTEGER NOT NULL DEFAULT 0,
            updated_at TIMESTAMP NOT NULL
          )
          """);
      statement.execute(
          """
          CREATE TABLE task_executions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id VARCHAR(255) NOT NULL,
            session_id VARCHAR(512),
            started_at TIMESTAMP NOT NULL,
            success BOOLEAN NOT NULL,
            error_message TEXT,
            duration_ms INTEGER NOT NULL
          )
          """);
      statement.execute(
          """
          INSERT INTO scheduled_tasks (
            task_id, profile_name, cron, zone, message, enabled, next_run_at, last_run_at,
            last_status, run_count, updated_at)
          VALUES (
            'daily', 'legacy-agent', '0 0 0 1 1 *', 'Asia/Shanghai', 'legacy message', 0,
            '2026-01-01T00:00:00Z', '2025-12-31T00:00:00Z', 'success', 7,
            '2025-12-31T00:00:00Z')
          """);
      statement.execute(
          """
          INSERT INTO task_executions (
            task_id, session_id, started_at, success, error_message, duration_ms)
          VALUES ('daily', 'legacy-session', '2025-12-31T00:00:00Z', 1, NULL, 42)
          """);
      statement.execute(
          """
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
          """);
      statement.execute(
          "INSERT INTO llm_calls (session_id, provider, model, success, duration_ms, created_at)"
              + " VALUES ('s-1', 'deepseek', 'deepseek-chat', 1, 100, '2025-12-31T00:00:00Z')");
      statement.execute(
          """
          CREATE TABLE memory_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scope VARCHAR(16) NOT NULL,
            content TEXT NOT NULL,
            created_at TIMESTAMP NOT NULL
          )
          """);
      statement.execute(
          "INSERT INTO memory_entries (scope, content, created_at)"
              + " VALUES ('ARCHIVAL', 'legacy memory', '2025-12-31T00:00:00Z')");
      statement.execute(
          """
          CREATE TABLE notify_channels (
            name VARCHAR(128) PRIMARY KEY,
            type VARCHAR(32) NOT NULL,
            url TEXT NOT NULL,
            description TEXT,
            created_at TIMESTAMP NOT NULL,
            updated_at TIMESTAMP NOT NULL
          )
          """);
      statement.execute(
          "INSERT INTO notify_channels (name, type, url, created_at, updated_at)"
              + " VALUES ('ops', 'webhook', 'https://example.com/hook',"
              + " '2025-12-31T00:00:00Z', '2025-12-31T00:00:00Z')");
    }
  }

  private static void assertNewSchemaAndPreservedRows(String dbUrl) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement()) {
      assertFalse(columnExists(statement, "scheduled_tasks", "task_id"));
      assertTrue(columnExists(statement, "scheduled_tasks", "schedule_id"));
      assertTrue(columnExists(statement, "task_executions", "schedule_id"));
      assertTrue(columnExists(statement, "task_executions", "legacy_task_key"));
      assertTrue(columnExists(statement, "task_executions", "legacy_migrated"));
      assertEquals(1, count(statement, "scheduled_tasks"));
      assertEquals(1, count(statement, "task_executions"));

      // V2：审计补列；V3：记忆补列；V5：渠道补列——存量行全部完好
      assertTrue(columnExists(statement, "llm_calls", "trace_id"));
      assertTrue(columnExists(statement, "llm_calls", "cost_micros"));
      assertTrue(columnExists(statement, "llm_calls", "profile_name"));
      assertTrue(columnExists(statement, "memory_entries", "agent_name"));
      assertTrue(columnExists(statement, "notify_channels", "config"));
      assertEquals(1, count(statement, "llm_calls"));
      assertEquals(1, count(statement, "memory_entries"));
      assertEquals(1, count(statement, "notify_channels"));

      try (ResultSet task =
          statement.executeQuery(
              "SELECT schedule_id, profile_name, schedule_key, display_name, enabled, run_count"
                  + " FROM scheduled_tasks")) {
        assertTrue(task.next());
        assertNotNull(task.getString("schedule_id"));
        assertFalse(task.getString("schedule_id").isBlank());
        assertEquals("legacy-agent", task.getString("profile_name"));
        assertEquals("daily", task.getString("schedule_key"));
        assertEquals("daily", task.getString("display_name"));
        assertFalse(task.getBoolean("enabled"));
        assertEquals(7, task.getInt("run_count"));
      }

      try (ResultSet execution =
          statement.executeQuery(
              "SELECT schedule_id, legacy_task_key, legacy_migrated FROM task_executions")) {
        assertTrue(execution.next());
        assertNotNull(execution.getString("schedule_id"));
        assertEquals("daily", execution.getString("legacy_task_key"));
        assertTrue(execution.getBoolean("legacy_migrated"));
      }
    }
  }

  private static long flywayHistoryRows(String dbUrl) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static boolean columnExists(Statement statement, String table, String column)
      throws Exception {
    try (ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        if (column.equals(rows.getString("name"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static long count(Statement statement, String table) throws Exception {
    try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-legacy-takeover");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("legacy-agent"));
    Files.writeString(
        root.resolve("agents/legacy-agent/AGENT.md"),
        """
        ---
        name: legacy-agent
        description: legacy upgrade fixture
        identity:
          agent_name: Legacy Agent
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        schedules:
          - key: daily
            name: Daily check
            cron: "0 0 0 1 1 *"
            zone: Asia/Shanghai
            message: current message
        settings:
          max_iterations: 1
          max_history_turns: 1
        ---
        Test fixture.
        """);
    return root;
  }
}
