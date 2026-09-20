package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V16：组织 {@code parent_org_id} 自引用（#566）。存量 {@code organizations} 用列探测后 {@code ALTER TABLE ADD
 * COLUMN}。与 PostgreSQL 目录 {@code V16__organizations_parent.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 *
 * <p>SQLite 不建 FK；删除父组织时由 {@code OrganizationCatalogService.delete} 清空子节点 parent。
 */
final class OrganizationsParentMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(OrganizationsParentMigration.class);

  private static final String PARENT_ORG_ID = "parent_org_id";

  OrganizationsParentMigration() {
    super("16", "organizations parent_org_id");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    Set<String> orgColumns = columns(connection, "organizations");
    if (!orgColumns.isEmpty() && !orgColumns.contains(PARENT_ORG_ID)) {
      execute(connection, "ALTER TABLE organizations ADD COLUMN parent_org_id VARCHAR(128) NULL");
    }
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_organizations_parent_org_id"
            + " ON organizations (parent_org_id)");
    log.info("organizations.parent_org_id 已收敛");
  }
}
