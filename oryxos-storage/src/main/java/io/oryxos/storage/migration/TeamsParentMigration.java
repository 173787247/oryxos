package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V17：团队 {@code parent_team_id} 自引用（#581）。存量 {@code teams} 用列探测后 {@code ALTER TABLE ADD COLUMN}。与
 * PostgreSQL 目录 {@code V17__teams_parent.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 *
 * <p>SQLite 不建 FK；删除父团队时由 {@code TeamCatalogService.delete} 清空子节点 parent。
 */
final class TeamsParentMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(TeamsParentMigration.class);

  private static final String PARENT_TEAM_ID = "parent_team_id";

  TeamsParentMigration() {
    super("17", "teams parent_team_id");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    Set<String> teamColumns = columns(connection, "teams");
    if (!teamColumns.isEmpty() && !teamColumns.contains(PARENT_TEAM_ID)) {
      execute(connection, "ALTER TABLE teams ADD COLUMN parent_team_id VARCHAR(128) NULL");
    }
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_teams_parent_team_id" + " ON teams (parent_team_id)");
    log.info("teams.parent_team_id 已收敛");
  }
}
