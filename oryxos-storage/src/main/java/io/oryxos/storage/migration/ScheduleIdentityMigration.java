package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V4（原 ScheduleSchemaUpgrade 平移，28 节）：调度表结构检测式收敛——三分支状态机：当前结构仅补执行索引； pre-retirement 结构补 retired
 * 列；legacy 结构整表重建（task_id → 随机 schedule_id 键映射 + 行数对账）。 结构无法识别（非本产品所建或被手工改动）时抛异常拒启，绝不带病修改数据。
 *
 * <p>与原类唯一的行为差异：手写 BEGIN IMMEDIATE/COMMIT/ROLLBACK 移除——Flyway 以事务包裹整个迁移， 异常上抛即整体回滚，对账失败同样回滚。
 */
final class ScheduleIdentityMigration extends BaseSqliteMigration {

  private static final Set<String> LEGACY_TASK_COLUMNS =
      Set.of(
          "task_id",
          "profile_name",
          "cron",
          "zone",
          "message",
          "enabled",
          "next_run_at",
          "last_run_at",
          "last_status",
          "run_count",
          "updated_at");
  private static final Set<String> LEGACY_EXECUTION_COLUMNS =
      Set.of(
          "id", "task_id", "session_id", "started_at", "success", "error_message", "duration_ms");
  private static final Set<String> CURRENT_TASK_COLUMNS =
      Set.of(
          "schedule_id",
          "profile_name",
          "schedule_key",
          "display_name",
          "cron",
          "zone",
          "message",
          "enabled",
          "retired",
          "next_run_at",
          "last_run_at",
          "last_status",
          "run_count",
          "updated_at");
  private static final Set<String> CURRENT_EXECUTION_COLUMNS =
      Set.of(
          "id",
          "schedule_id",
          "legacy_task_key",
          "legacy_migrated",
          "session_id",
          "started_at",
          "success",
          "error_message",
          "duration_ms");

  ScheduleIdentityMigration() {
    super("4", "schedule identity");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    Set<String> taskColumns = columns(connection, "scheduled_tasks");
    Set<String> executionColumns = columns(connection, "task_executions");
    if (taskColumns.isEmpty() && executionColumns.isEmpty()) {
      return;
    }
    if (isCurrent(connection, taskColumns, executionColumns)) {
      ensureExecutionIndex(connection);
      return;
    }
    if (isPreRetirementCurrent(connection, taskColumns, executionColumns)) {
      execute(
          connection, "ALTER TABLE scheduled_tasks ADD COLUMN retired BOOLEAN NOT NULL DEFAULT 0");
      ensureExecutionIndex(connection);
      return;
    }
    if (!isLegacy(taskColumns, executionColumns)) {
      throw unsupported(taskColumns, executionColumns);
    }
    rebuildLegacyTables(connection);
  }

  private static void ensureExecutionIndex(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_task_executions_schedule ON task_executions (schedule_id)");
  }

  private static boolean isCurrent(
      Connection connection, Set<String> taskColumns, Set<String> executionColumns)
      throws SQLException {
    return CURRENT_TASK_COLUMNS.equals(taskColumns)
        && CURRENT_EXECUTION_COLUMNS.equals(executionColumns)
        && hasScheduledTaskPrimaryKey(connection)
        && hasScheduleIdentityUniqueIndex(connection);
  }

  private static boolean isPreRetirementCurrent(
      Connection connection, Set<String> taskColumns, Set<String> executionColumns)
      throws SQLException {
    Set<String> preRetirementTaskColumns = new HashSet<>(CURRENT_TASK_COLUMNS);
    preRetirementTaskColumns.remove("retired");
    return preRetirementTaskColumns.equals(taskColumns)
        && CURRENT_EXECUTION_COLUMNS.equals(executionColumns)
        && hasScheduledTaskPrimaryKey(connection)
        && hasScheduleIdentityUniqueIndex(connection);
  }

  private static boolean hasScheduledTaskPrimaryKey(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(scheduled_tasks)")) {
      while (rows.next()) {
        if ("schedule_id".equals(rows.getString("name")) && rows.getInt("pk") == 1) {
          return true;
        }
      }
      return false;
    }
  }

  private static boolean hasScheduleIdentityUniqueIndex(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet indexes = statement.executeQuery("PRAGMA index_list(scheduled_tasks)")) {
      while (indexes.next()) {
        if (indexes.getInt("unique") != 1) {
          continue;
        }
        String indexName = indexes.getString("name");
        Set<String> indexedColumns = new HashSet<>();
        try (PreparedStatement indexStatement =
            connection.prepareStatement("SELECT name FROM pragma_index_info(?)")) {
          indexStatement.setString(1, indexName);
          try (ResultSet indexColumns = indexStatement.executeQuery()) {
            while (indexColumns.next()) {
              indexedColumns.add(indexColumns.getString("name"));
            }
          }
        }
        if (indexedColumns.equals(Set.of("profile_name", "schedule_key"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static boolean isLegacy(Set<String> taskColumns, Set<String> executionColumns) {
    return taskColumns.equals(LEGACY_TASK_COLUMNS)
        && executionColumns.equals(LEGACY_EXECUTION_COLUMNS);
  }

  private static IllegalStateException unsupported(
      Set<String> taskColumns, Set<String> executionColumns) {
    return new IllegalStateException(
        "Unsupported scheduled-task schema; refusing to modify data. scheduled_tasks="
            + taskColumns
            + ", task_executions="
            + executionColumns);
  }

  private static void rebuildLegacyTables(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      long taskRowCount = scheduledTaskRowCount(connection);
      long executionRowCount = taskExecutionRowCount(connection);
      createReplacementTables(statement);
      Map<String, String> legacyToScheduleId = copyTasks(connection);
      copyExecutions(connection, legacyToScheduleId);
      statement.execute("DROP TABLE scheduled_tasks");
      statement.execute("DROP TABLE task_executions");
      statement.execute("ALTER TABLE scheduled_tasks_new RENAME TO scheduled_tasks");
      statement.execute("ALTER TABLE task_executions_new RENAME TO task_executions");
      statement.execute(
          "CREATE INDEX idx_task_executions_schedule ON task_executions (schedule_id)");
      verifyRowCounts(connection, taskRowCount, executionRowCount);
    }
  }

  private static void createReplacementTables(Statement statement) throws SQLException {
    statement.execute(
        """
        CREATE TABLE scheduled_tasks_new (
          schedule_id VARCHAR(36) PRIMARY KEY,
          profile_name VARCHAR(255) NOT NULL,
          schedule_key VARCHAR(255) NOT NULL,
          display_name VARCHAR(255) NOT NULL,
          cron VARCHAR(128) NOT NULL,
          zone VARCHAR(64),
          message TEXT,
          enabled BOOLEAN NOT NULL DEFAULT 1,
          retired BOOLEAN NOT NULL DEFAULT 0,
          next_run_at TIMESTAMP,
          last_run_at TIMESTAMP,
          last_status VARCHAR(16),
          run_count INTEGER NOT NULL DEFAULT 0,
          updated_at TIMESTAMP NOT NULL,
          UNIQUE (profile_name, schedule_key)
        )
        """);
    statement.execute(
        """
        CREATE TABLE task_executions_new (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          schedule_id VARCHAR(36),
          legacy_task_key VARCHAR(255),
          legacy_migrated BOOLEAN NOT NULL DEFAULT 0,
          session_id VARCHAR(512),
          started_at TIMESTAMP NOT NULL,
          success BOOLEAN NOT NULL,
          error_message TEXT,
          duration_ms INTEGER NOT NULL
        )
        """);
  }

  private static Map<String, String> copyTasks(Connection connection) throws SQLException {
    Map<String, String> legacyToScheduleId = new HashMap<>(16);
    try (Statement select = connection.createStatement();
        ResultSet rows = select.executeQuery("SELECT * FROM scheduled_tasks");
        PreparedStatement insert =
            connection.prepareStatement(
                """
                INSERT INTO scheduled_tasks_new (
                  schedule_id, profile_name, schedule_key, display_name, cron, zone, message, enabled,
                  retired, next_run_at, last_run_at, last_status, run_count, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
      while (rows.next()) {
        String legacyKey = rows.getString("task_id");
        String scheduleId = UUID.randomUUID().toString();
        legacyToScheduleId.put(legacyKey, scheduleId);
        insert.setString(1, scheduleId);
        insert.setString(2, rows.getString("profile_name"));
        insert.setString(3, legacyKey);
        insert.setString(4, legacyKey);
        insert.setString(5, rows.getString("cron"));
        insert.setString(6, rows.getString("zone"));
        insert.setString(7, rows.getString("message"));
        insert.setObject(8, rows.getObject("enabled"));
        insert.setBoolean(9, false);
        insert.setObject(10, rows.getObject("next_run_at"));
        insert.setObject(11, rows.getObject("last_run_at"));
        insert.setString(12, rows.getString("last_status"));
        insert.setObject(13, rows.getObject("run_count"));
        insert.setObject(14, rows.getObject("updated_at"));
        insert.executeUpdate();
      }
    }
    return legacyToScheduleId;
  }

  private static void copyExecutions(Connection connection, Map<String, String> legacyToScheduleId)
      throws SQLException {
    try (Statement select = connection.createStatement();
        ResultSet rows = select.executeQuery("SELECT * FROM task_executions");
        PreparedStatement insert =
            connection.prepareStatement(
                """
                INSERT INTO task_executions_new (
                  id, schedule_id, legacy_task_key, legacy_migrated, session_id, started_at, success,
                  error_message, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
      while (rows.next()) {
        String legacyKey = rows.getString("task_id");
        String scheduleId = legacyToScheduleId.get(legacyKey);
        insert.setObject(1, rows.getObject("id"));
        if (scheduleId == null) {
          insert.setNull(2, Types.VARCHAR);
        } else {
          insert.setString(2, scheduleId);
        }
        insert.setString(3, legacyKey);
        insert.setBoolean(4, true);
        insert.setString(5, rows.getString("session_id"));
        insert.setObject(6, rows.getObject("started_at"));
        insert.setObject(7, rows.getObject("success"));
        insert.setString(8, rows.getString("error_message"));
        insert.setObject(9, rows.getObject("duration_ms"));
        insert.executeUpdate();
      }
    }
  }

  private static long scheduledTaskRowCount(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM scheduled_tasks")) {
      if (!rows.next()) {
        throw new SQLException("Could not count rows in scheduled_tasks");
      }
      return rows.getLong(1);
    }
  }

  private static long taskExecutionRowCount(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM task_executions")) {
      if (!rows.next()) {
        throw new SQLException("Could not count rows in task_executions");
      }
      return rows.getLong(1);
    }
  }

  private static void verifyRowCounts(
      Connection connection, long expectedTaskRows, long expectedExecutionRows)
      throws SQLException {
    long actualTaskRows = scheduledTaskRowCount(connection);
    long actualExecutionRows = taskExecutionRowCount(connection);
    if (actualTaskRows != expectedTaskRows || actualExecutionRows != expectedExecutionRows) {
      throw new SQLException(
          "Scheduled-task migration changed row counts: tasks "
              + expectedTaskRows
              + " -> "
              + actualTaskRows
              + ", executions "
              + expectedExecutionRows
              + " -> "
              + actualExecutionRows);
    }
  }
}
