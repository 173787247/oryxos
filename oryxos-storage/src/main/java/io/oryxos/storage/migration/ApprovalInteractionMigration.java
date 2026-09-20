package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** V20：044 审批交互（#466）— 检查点时效列 + 回调收据表。与 PostgreSQL {@code V20__approval_interaction.sql} 成对。 */
final class ApprovalInteractionMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(ApprovalInteractionMigration.class);

  ApprovalInteractionMigration() {
    super("20", "approval interaction");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    Set<String> cols = columns(connection, "durable_task_checkpoints");
    if (!cols.isEmpty() && !cols.contains("ttl_seconds")) {
      execute(connection, "ALTER TABLE durable_task_checkpoints ADD COLUMN ttl_seconds INTEGER");
    }
    if (!cols.isEmpty() && !cols.contains("expires_at")) {
      execute(connection, "ALTER TABLE durable_task_checkpoints ADD COLUMN expires_at TIMESTAMP");
    }
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS approval_callback_receipts ("
            + "channel VARCHAR(64) NOT NULL,"
            + "callback_id VARCHAR(255) NOT NULL,"
            + "checkpoint_id VARCHAR(64),"
            + "created_at TIMESTAMP NOT NULL,"
            + "PRIMARY KEY (channel, callback_id))");
    log.info("approval interaction schema 已收敛");
  }
}
