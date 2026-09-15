package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V9：039 Web 用户角色。SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，用 PRAGMA 探测后补列；与 PostgreSQL 目录的 {@code
 * V9__web_user_roles.sql} 成对。幂等重跑支撑 MigrationEvolutionIT 尾部中断恢复（与 V8 CREATE IF NOT EXISTS 同语义）。
 */
final class WebUserRolesMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(WebUserRolesMigration.class);

  private static final String ROLES = "roles";

  WebUserRolesMigration() {
    super("9", "web user roles");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    ensureRolesColumn(connection);
    ensureAuthzEvents(connection);
  }

  private static void ensureRolesColumn(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "web_users");
    if (columns.isEmpty()) {
      return;
    }
    if (!columns.contains(ROLES)) {
      execute(
          connection,
          "ALTER TABLE web_users ADD COLUMN roles VARCHAR(255) NOT NULL DEFAULT 'VIEWER'");
    }
    log.info("web_users 已收敛 roles 列");
  }

  private static void ensureAuthzEvents(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS authz_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "principal_kind VARCHAR(16) NOT NULL,"
            + "principal_id VARCHAR(128) NOT NULL,"
            + "action VARCHAR(32) NOT NULL,"
            + "resource_type VARCHAR(32),"
            + "resource_id VARCHAR(255),"
            + "reason VARCHAR(512) NOT NULL,"
            + "request_method VARCHAR(16),"
            + "request_path VARCHAR(512),"
            + "trace_id VARCHAR(64),"
            + "created_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_authz_events_created_at ON authz_events (created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_authz_events_principal ON authz_events (principal_id, created_at)");
  }
}
