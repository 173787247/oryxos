package io.oryxos.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * memory_entries 的结构检测式幂等升级（015 FR-014，照 ScheduleSchemaUpgrade 先例）： 存量库缺 agent_name 列时 PRAGMA 检测 →
 * ALTER ADD COLUMN 归 '__global__' 占位 + 建 (agent_name, scope) 索引， 并打一次可读迁移日志；新装库/已升级库自然跳过。SQLite 的
 * ALTER ADD COLUMN 带常量 DEFAULT 是受支持的窄路径， 不需要 Schedule 那样的整表重建。
 */
public final class MemorySchemaUpgrade {

  private static final Logger log = LoggerFactory.getLogger(MemorySchemaUpgrade.class);

  private static final String AGENT_COLUMN = "agent_name";

  private final DataSource dataSource;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected DataSource is an intentionally shared connection factory and cannot be defensively copied.")
  public MemorySchemaUpgrade(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 表不存在（schema.sql 会全量建）或已有 agent_name 列则跳过；否则补列 + 建索引。 */
  public void upgrade() {
    try (Connection connection = dataSource.getConnection()) {
      Set<String> columns = memoryEntryColumns(connection);
      if (columns.isEmpty()) {
        return;
      }
      if (columns.contains(AGENT_COLUMN)) {
        ensureAgentIndex(connection);
        return;
      }
      addAgentColumn(connection);
      ensureAgentIndex(connection);
      log.info(
          "memory_entries 已补 agent_name 列（015 记忆作用域升级）：存量记忆归 '{}' 全局作用域，行为与升级前一致",
          MemoryEntry.GLOBAL_AGENT);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to upgrade memory_entries schema", e);
    }
  }

  private static void addAgentColumn(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE memory_entries ADD COLUMN agent_name VARCHAR(128) NOT NULL DEFAULT '"
              + MemoryEntry.GLOBAL_AGENT
              + "'");
    }
  }

  private static void ensureAgentIndex(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE INDEX IF NOT EXISTS idx_memory_agent ON memory_entries (agent_name, scope)");
    }
  }

  private static Set<String> memoryEntryColumns(Connection connection) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(memory_entries)")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    return columns;
  }
}
