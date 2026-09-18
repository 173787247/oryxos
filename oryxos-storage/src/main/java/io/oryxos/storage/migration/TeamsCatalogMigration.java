package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V14：团队目录（#539）。新表用 {@code CREATE TABLE IF NOT EXISTS}；与 PostgreSQL 目录 {@code
 * V14__teams_catalog.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 */
final class TeamsCatalogMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(TeamsCatalogMigration.class);

  TeamsCatalogMigration() {
    super("14", "teams catalog");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS teams ("
            + "team_id VARCHAR(128) PRIMARY KEY,"
            + "display_name VARCHAR(255) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL)");
    execute(
        connection, "CREATE INDEX IF NOT EXISTS idx_teams_display_name ON teams (display_name)");
    log.info("teams 目录表已收敛");
  }
}
