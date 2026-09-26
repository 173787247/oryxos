package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** V25：Direction I team-task runs。与 PostgreSQL {@code V25__team_task_runs.sql} 成对。 */
final class TeamTaskRunsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(TeamTaskRunsMigration.class);

  TeamTaskRunsMigration() {
    super("25", "team task runs");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS team_task_runs ("
            + "id VARCHAR(64) PRIMARY KEY,"
            + "goal TEXT NOT NULL,"
            + "coordinator VARCHAR(255) NOT NULL,"
            + "plan_raw TEXT,"
            + "workers_json TEXT NOT NULL,"
            + "summary TEXT,"
            + "created_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_team_task_runs_created ON team_task_runs (created_at)");
    log.info("team_task_runs ready (V25)");
  }
}
