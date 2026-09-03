package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V2（原 AuditSchemaUpgrade 平移，016/020/021）：审计表幂等补列 + 后加列索引。存量库缺列时 ALTER ADD COLUMN 补可空列；新库（V1
 * 已含全列）自然跳过补列、仅补建索引。原类的 llm_pricing 建表已去重——该表 DDL 的唯一 真相源是 V1 基线（llm_pricing 双份 DDL 收敛，025）。
 */
final class AuditColumnsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(AuditColumnsMigration.class);

  private static final String COST_MICROS_COLUMN = "cost_micros";
  private static final String PROFILE_NAME_COLUMN = "profile_name";
  private static final String BLOCKED_BY_COLUMN = "blocked_by";
  private static final String TRACE_ID_COLUMN = "trace_id";

  /** 021：trace_id 落到审计三表（llm_calls / tool_invocations / agent_executions）。 */
  private static final String[] TRACE_TABLES = {
    "llm_calls", "tool_invocations", "agent_executions"
  };

  AuditColumnsMigration() {
    super("2", "audit columns");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    ensureLlmCallColumns(connection);
    ensureToolInvocationColumns(connection);
    ensureProfileIndexes(connection);
    ensureTraceColumnsAndIndexes(connection);
  }

  private static void ensureLlmCallColumns(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "llm_calls");
    if (columns.isEmpty()) {
      return; // 表不存在的防御分支：V1 必先跑，正常不可达
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

  private static void ensureToolInvocationColumns(Connection connection) throws SQLException {
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

  /** 021：三表补 trace_id 可空列 + 建 trace 索引。索引在这里建而不是 V1——存量库跑 V1 时还没有该列。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志占位符只填 TRACE_TABLES 内部常量表名，非用户输入，无 CRLF 注入面。")
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

  private static void ensureProfileIndexes(Connection connection) throws SQLException {
    execute(
        connection, "CREATE INDEX IF NOT EXISTS idx_llm_calls_profile ON llm_calls (profile_name)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_tool_invocations_profile ON tool_invocations (profile_name)");
  }
}
