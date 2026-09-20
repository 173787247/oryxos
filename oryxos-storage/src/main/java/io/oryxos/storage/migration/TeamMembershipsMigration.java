package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V12：持久化团队成员（#535）。新表用 {@code CREATE TABLE IF NOT EXISTS}；与 PostgreSQL 目录 {@code
 * V12__team_memberships.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 */
final class TeamMembershipsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(TeamMembershipsMigration.class);

  TeamMembershipsMigration() {
    super("12", "team memberships");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS team_memberships ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "username VARCHAR(64) NOT NULL,"
            + "team_id VARCHAR(128) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL,"
            + "CONSTRAINT uq_team_memberships_user_team UNIQUE (username, team_id))");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_team_memberships_username"
            + " ON team_memberships (username)");
    log.info("team_memberships 已收敛");
  }
}
