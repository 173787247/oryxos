package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** V19：043 耐久任务检查点（#465）。与 PostgreSQL 目录 {@code V19__durable_task_checkpoints.sql} 成对。 */
final class DurableTaskCheckpointsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(DurableTaskCheckpointsMigration.class);

  DurableTaskCheckpointsMigration() {
    super("19", "durable task checkpoints");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS durable_task_checkpoints ("
            + "id VARCHAR(64) PRIMARY KEY,"
            + "execution_id BIGINT,"
            + "session_id VARCHAR(128),"
            + "agent_name VARCHAR(255) NOT NULL,"
            + "state VARCHAR(32) NOT NULL,"
            + "idempotency_key VARCHAR(255) NOT NULL,"
            + "checkpoint_kind VARCHAR(64) NOT NULL,"
            + "tool_name VARCHAR(255),"
            + "tool_call_id VARCHAR(128),"
            + "arguments_json TEXT,"
            + "policy_version VARCHAR(64),"
            + "rule_id VARCHAR(128),"
            + "attempt INTEGER NOT NULL DEFAULT 0,"
            + "last_error VARCHAR(1024),"
            + "created_at TIMESTAMP NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE UNIQUE INDEX IF NOT EXISTS uk_durable_task_checkpoints_idempotency "
            + "ON durable_task_checkpoints (idempotency_key)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_durable_task_checkpoints_state "
            + "ON durable_task_checkpoints (state)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_durable_task_checkpoints_execution "
            + "ON durable_task_checkpoints (execution_id)");
    log.info("durable_task_checkpoints 已收敛");
  }
}
