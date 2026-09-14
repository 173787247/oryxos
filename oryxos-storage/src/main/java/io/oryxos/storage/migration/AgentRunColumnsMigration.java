package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V6：流式 Run 工作台。SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，用 PRAGMA 探测后补列；与 PostgreSQL 目录的 {@code
 * V6__agent_run_workbench.sql} 成对。
 */
final class AgentRunColumnsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(AgentRunColumnsMigration.class);

  private static final String UPDATED_AT = "updated_at";
  private static final String INPUT_PREVIEW = "input_preview";
  private static final String CANCEL_REQUESTED_AT = "cancel_requested_at";
  private static final String STATUS = "status";
  private static final String STOP_REASON = "stop_reason";

  AgentRunColumnsMigration() {
    super("6", "agent run workbench");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    ensureExecutionColumns(connection);
    backfillStatus(connection);
    ensureEventTable(connection);
  }

  private static void ensureExecutionColumns(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "agent_executions");
    if (columns.isEmpty()) {
      return;
    }
    if (!columns.contains(UPDATED_AT)) {
      execute(connection, "ALTER TABLE agent_executions ADD COLUMN updated_at TIMESTAMP");
    }
    if (!columns.contains(INPUT_PREVIEW)) {
      execute(connection, "ALTER TABLE agent_executions ADD COLUMN input_preview TEXT");
    }
    if (!columns.contains(CANCEL_REQUESTED_AT)) {
      execute(connection, "ALTER TABLE agent_executions ADD COLUMN cancel_requested_at TIMESTAMP");
    }
    if (!columns.contains(STATUS)) {
      execute(connection, "ALTER TABLE agent_executions ADD COLUMN status VARCHAR(32)");
    }
    if (!columns.contains(STOP_REASON)) {
      execute(connection, "ALTER TABLE agent_executions ADD COLUMN stop_reason VARCHAR(64)");
    }
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_agent_executions_started ON agent_executions (started_at, id)");
    log.info("agent_executions 已收敛 Run 工作台列");
  }

  private static void backfillStatus(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "agent_executions");
    if (!columns.contains(STATUS)) {
      return;
    }
    execute(
        connection,
        "UPDATE agent_executions SET status = 'RUNNING' WHERE status IS NULL AND ended_at IS NULL");
    execute(
        connection,
        "UPDATE agent_executions SET status = 'SUCCESS' WHERE status IS NULL AND success = 1");
    execute(
        connection,
        "UPDATE agent_executions SET status = 'FAILED' WHERE status IS NULL AND ended_at IS NOT NULL");
    execute(
        connection,
        "UPDATE agent_executions SET updated_at = COALESCE(ended_at, started_at) WHERE updated_at IS NULL");
  }

  private static void ensureEventTable(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS agent_run_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "run_id INTEGER NOT NULL,"
            + "sequence INTEGER NOT NULL,"
            + "type VARCHAR(64) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL,"
            + "payload_json TEXT NOT NULL,"
            + "UNIQUE (run_id, sequence))");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_agent_run_events_run_seq ON agent_run_events (run_id, sequence)");
  }
}
