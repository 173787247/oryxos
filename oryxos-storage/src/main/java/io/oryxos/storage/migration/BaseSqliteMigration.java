package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

/**
 * SQLite 存量库幂等迁移基类（025）：V2~V5 是原四个手工升级类的平移——幂等依据仍是 {@code PRAGMA table_info} 列探测，任意历史状态的存量库经 V1~V5
 * 收敛到同一最终结构；新库列已全、探测后空转。
 *
 * <p>不用 {@code BaseJavaMigration}：它强制从类名解析版本（{@code V2__x} 命名违反本仓库类名规范）， 这里显式给出版本与描述。checksum
 * 返回固定值——迁移逻辑幂等且只增不改，history 校验以版本为准。
 */
abstract class BaseSqliteMigration implements JavaMigration {

  private final MigrationVersion version;
  private final String description;

  BaseSqliteMigration(String version, String description) {
    this.version = MigrationVersion.fromVersion(version);
    this.description = description;
  }

  @Override
  public MigrationVersion getVersion() {
    return version;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public Integer getChecksum() {
    return 0;
  }

  @Override
  public boolean canExecuteInTransaction() {
    return true;
  }

  @Override
  public void migrate(Context context) throws Exception {
    migrate(context.getConnection());
  }

  abstract void migrate(Connection connection) throws SQLException;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
      justification = "table 参数由各迁移类内部硬编码常量传入，非用户输入，无注入风险。")
  static Set<String> columns(Connection connection, String table) throws SQLException {
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
      justification = "sql 参数为迁移类内部硬编码的 ALTER/CREATE 语句，非用户输入，无注入风险。")
  static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
