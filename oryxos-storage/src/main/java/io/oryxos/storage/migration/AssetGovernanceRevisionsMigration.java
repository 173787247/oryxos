package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V13：资产治理版本快照（#537）。新表用 {@code CREATE TABLE IF NOT EXISTS}；与 PostgreSQL 目录 {@code
 * V13__asset_governance_revisions.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 */
final class AssetGovernanceRevisionsMigration extends BaseSqliteMigration {

  private static final Logger log =
      LoggerFactory.getLogger(AssetGovernanceRevisionsMigration.class);

  AssetGovernanceRevisionsMigration() {
    super("13", "asset governance revisions");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS asset_governance_revisions ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "resource_type VARCHAR(32) NOT NULL,"
            + "resource_id VARCHAR(255) NOT NULL,"
            + "version_label VARCHAR(128),"
            + "snapshot_text TEXT NOT NULL,"
            + "actor VARCHAR(128) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_asset_gov_revisions_resource"
            + " ON asset_governance_revisions (resource_type, resource_id, created_at DESC)");
    log.info("asset_governance_revisions 已收敛");
  }
}
