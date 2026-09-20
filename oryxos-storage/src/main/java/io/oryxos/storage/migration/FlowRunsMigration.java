package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** V21：046 Flow runs/steps（#468）。与 PostgreSQL {@code V21__flow_runs.sql} 成对。 */
final class FlowRunsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(FlowRunsMigration.class);

  FlowRunsMigration() {
    super("21", "flow runs and steps");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS flow_runs ("
            + "id VARCHAR(64) PRIMARY KEY,"
            + "flow_id VARCHAR(255) NOT NULL,"
            + "flow_version VARCHAR(64) NOT NULL,"
            + "definition_markdown TEXT NOT NULL,"
            + "state VARCHAR(32) NOT NULL,"
            + "entry_node_id VARCHAR(128),"
            + "current_node_id VARCHAR(128),"
            + "inputs_json TEXT,"
            + "context_json TEXT,"
            + "last_error VARCHAR(1024),"
            + "attempt INTEGER NOT NULL DEFAULT 0,"
            + "created_at TIMESTAMP NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL)");
    execute(connection, "CREATE INDEX IF NOT EXISTS idx_flow_runs_state ON flow_runs (state)");
    execute(connection, "CREATE INDEX IF NOT EXISTS idx_flow_runs_flow_id ON flow_runs (flow_id)");
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS flow_steps ("
            + "id VARCHAR(64) PRIMARY KEY,"
            + "run_id VARCHAR(64) NOT NULL,"
            + "node_id VARCHAR(128) NOT NULL,"
            + "node_type VARCHAR(32) NOT NULL,"
            + "state VARCHAR(32) NOT NULL,"
            + "attempt INTEGER NOT NULL DEFAULT 0,"
            + "idempotency_key VARCHAR(255) NOT NULL,"
            + "inputs_json TEXT,"
            + "outputs_json TEXT,"
            + "error VARCHAR(1024),"
            + "started_at TIMESTAMP,"
            + "finished_at TIMESTAMP,"
            + "created_at TIMESTAMP NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE UNIQUE INDEX IF NOT EXISTS uk_flow_steps_idempotency ON flow_steps (idempotency_key)");
    execute(connection, "CREATE INDEX IF NOT EXISTS idx_flow_steps_run ON flow_steps (run_id)");
    log.info("flow_runs / flow_steps 已收敛");
  }
}
