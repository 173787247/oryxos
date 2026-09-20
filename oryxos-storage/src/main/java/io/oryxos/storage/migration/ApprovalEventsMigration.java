package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V18：042 高风险审批审计（#464）。新表用 {@code CREATE TABLE IF NOT EXISTS}；与 PostgreSQL 目录 {@code
 * V18__approval_events.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 */
final class ApprovalEventsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(ApprovalEventsMigration.class);

  ApprovalEventsMigration() {
    super("18", "approval events");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS approval_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "kind VARCHAR(32) NOT NULL,"
            + "session_id VARCHAR(128),"
            + "agent_name VARCHAR(255),"
            + "tool_name VARCHAR(255),"
            + "action_type VARCHAR(64),"
            + "policy_version VARCHAR(64),"
            + "rule_id VARCHAR(128),"
            + "actor VARCHAR(128) NOT NULL,"
            + "reason VARCHAR(1024),"
            + "ttl_seconds INTEGER,"
            + "created_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_approval_events_created ON approval_events (created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_approval_events_kind ON approval_events (kind)");
    log.info("approval_events 已收敛");
  }
}
