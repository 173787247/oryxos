package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V15：组织目录 + {@code teams.org_id}（#554）。新表用 {@code CREATE TABLE IF NOT EXISTS}；存量 {@code teams}
 * 用列探测后 {@code ALTER TABLE ADD COLUMN}。与 PostgreSQL 目录 {@code V15__organizations_catalog.sql}
 * 成对。幂等重跑支撑 MigrationEvolutionIT。
 */
final class OrganizationsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(OrganizationsMigration.class);

  private static final String ORG_ID = "org_id";

  OrganizationsMigration() {
    super("15", "organizations catalog");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS organizations ("
            + "org_id VARCHAR(128) PRIMARY KEY,"
            + "display_name VARCHAR(255) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_organizations_display_name"
            + " ON organizations (display_name)");
    Set<String> teamColumns = columns(connection, "teams");
    if (!teamColumns.isEmpty() && !teamColumns.contains(ORG_ID)) {
      execute(connection, "ALTER TABLE teams ADD COLUMN org_id VARCHAR(128) NULL");
    }
    execute(connection, "CREATE INDEX IF NOT EXISTS idx_teams_org_id ON teams (org_id)");
    log.info("organizations 目录与 teams.org_id 已收敛");
  }
}
