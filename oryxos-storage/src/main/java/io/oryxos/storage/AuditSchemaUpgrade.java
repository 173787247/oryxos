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
 * 审计表 + 模型定价表的幂等结构升级（016 审计看板，照 ScheduleSchemaUpgrade / MemorySchemaUpgrade 先例）： 存量库缺列时 PRAGMA 检测 →
 * ALTER ADD COLUMN 补可空列 + 建 llm_pricing 表 + 建 profile_name 索引； 新装库（schema.sql 已全量建表）列已在、自然跳过补列，仅补建
 * profile_name 索引。所有新列可空，历史行不崩。
 */
public final class AuditSchemaUpgrade {

  private static final Logger log = LoggerFactory.getLogger(AuditSchemaUpgrade.class);

  private static final String COST_MICROS_COLUMN = "cost_micros";
  private static final String PROFILE_NAME_COLUMN = "profile_name";
  private static final String BLOCKED_BY_COLUMN = "blocked_by";
  private static final String TRACE_ID_COLUMN = "trace_id";

  /** 021：trace_id 落到审计三表（llm_calls / tool_invocations / agent_executions）。 */
  private static final String[] TRACE_TABLES = {
    "llm_calls", "tool_invocations", "agent_executions"
  };

  private final DataSource dataSource;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected DataSource is an intentionally shared connection factory and cannot be defensively copied.")
  public AuditSchemaUpgrade(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 幂等：补 llm_calls/tool_invocations 缺列、建 llm_pricing 表、建 profile_name 索引。 */
  public void upgrade() {
    try (Connection connection = dataSource.getConnection()) {
      ensureLlmCallColumns(connection);
      ensureToolInvocationColumn(connection);
      ensurePricingTable(connection);
      ensureProfileIndexes(connection);
      ensureTraceColumnsAndIndexes(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to upgrade audit schema", e);
    }
  }

  private static void ensureLlmCallColumns(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "llm_calls");
    if (columns.isEmpty()) {
      return; // 表不存在，schema.sql 会全量建
    }
    if (!columns.contains(COST_MICROS_COLUMN)) {
      execute(connection, "ALTER TABLE llm_calls ADD COLUMN cost_micros INTEGER");
      log.info("llm_calls 已补 cost_micros 列（016 成本写时定格）");
    }
    if (!columns.contains(PROFILE_NAME_COLUMN)) {
      execute(connection, "ALTER TABLE llm_calls ADD COLUMN profile_name VARCHAR(255)");
      log.info("llm_calls 已补 profile_name 列（016 Agent 归属）");
    }
  }

  private static void ensureToolInvocationColumn(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "tool_invocations");
    if (columns.isEmpty()) {
      return;
    }
    if (!columns.contains(PROFILE_NAME_COLUMN)) {
      execute(connection, "ALTER TABLE tool_invocations ADD COLUMN profile_name VARCHAR(255)");
      log.info("tool_invocations 已补 profile_name 列（016 Agent 归属）");
    }
    if (!columns.contains(BLOCKED_BY_COLUMN)) {
      execute(connection, "ALTER TABLE tool_invocations ADD COLUMN blocked_by VARCHAR(16)");
      log.info("tool_invocations 已补 blocked_by 列（020 策略拒绝标记）");
    }
  }

  /**
   * 021：三表补 trace_id 可空列 + 建 trace 索引。索引必须在这里建而不是 schema.sql——存量库执行 schema.sql
   * 时还没有该列，在脚本里建索引会让整个应用启动失败（idx_llm_calls_profile 同款教训）。表不存在时跳过（schema.sql 会全量建含新列）。
   */
  private static void ensureTraceColumnsAndIndexes(Connection connection) throws SQLException {
    for (String table : TRACE_TABLES) {
      Set<String> columns = columns(connection, table);
      if (columns.isEmpty()) {
        continue;
      }
      if (!columns.contains(TRACE_ID_COLUMN)) {
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN trace_id VARCHAR(64)");
        log.info("{} 已补 trace_id 列（021 单轮全链路串联）", table);
      }
      execute(
          connection,
          "CREATE INDEX IF NOT EXISTS idx_" + table + "_trace ON " + table + " (trace_id)");
    }
  }

  private static void ensurePricingTable(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS llm_pricing (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          provider VARCHAR(64) NOT NULL,
          model VARCHAR(128) NOT NULL,
          prompt_price REAL,
          completion_price REAL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          UNIQUE (provider, model)
        )
        """);
  }

  private static void ensureProfileIndexes(Connection connection) throws SQLException {
    execute(
        connection, "CREATE INDEX IF NOT EXISTS idx_llm_calls_profile ON llm_calls (profile_name)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_tool_invocations_profile ON tool_invocations (profile_name)");
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
      justification = "table 参数由内部硬编码常量传入（llm_calls/tool_invocations），非用户输入，无注入风险。")
  private static Set<String> columns(Connection connection, String table) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    return columns;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
      justification = "sql 参数为内部硬编码的 ALTER/CREATE 语句（表名来自 TRACE_TABLES 内部常量），非用户输入，无注入风险。")
  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
