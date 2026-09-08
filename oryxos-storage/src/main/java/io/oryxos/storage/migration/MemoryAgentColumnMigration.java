package io.oryxos.storage.migration;

import io.oryxos.storage.MemoryEntry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3（原 MemorySchemaUpgrade 平移，015 FR-014）：memory_entries 幂等补 agent_name 列（存量记忆归 '__global__'
 * 全局作用域，行为与升级前一致）+ 建 (agent_name, scope) 索引。SQLite 的 ALTER ADD COLUMN 带常量 DEFAULT 是受支持的窄路径，不需要
 * Schedule 那样的整表重建。
 */
final class MemoryAgentColumnMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(MemoryAgentColumnMigration.class);

  private static final String AGENT_COLUMN = "agent_name";

  MemoryAgentColumnMigration() {
    super("3", "memory agent column");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "memory_entries");
    if (columns.isEmpty()) {
      return;
    }
    if (!columns.contains(AGENT_COLUMN)) {
      execute(
          connection,
          "ALTER TABLE memory_entries ADD COLUMN agent_name VARCHAR(128) NOT NULL DEFAULT '"
              + MemoryEntry.GLOBAL_AGENT
              + "'");
      log.info(
          "memory_entries 已补 agent_name 列（015 记忆作用域升级）：存量记忆归 '{}' 全局作用域，行为与升级前一致",
          MemoryEntry.GLOBAL_AGENT);
    }
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_memory_agent ON memory_entries (agent_name, scope)");
  }
}
