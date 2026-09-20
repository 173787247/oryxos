package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** V22：047 Flow step expires_at（#469）。与 PostgreSQL {@code V22__flow_step_expires.sql} 成对。 */
final class FlowStepExpiresMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(FlowStepExpiresMigration.class);

  FlowStepExpiresMigration() {
    super("22", "flow step expires_at");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    Set<String> cols = columns(connection, "flow_steps");
    if (!cols.isEmpty() && !cols.contains("expires_at")) {
      execute(connection, "ALTER TABLE flow_steps ADD COLUMN expires_at TIMESTAMP");
    }
    log.info("flow_steps.expires_at 已收敛");
  }
}
