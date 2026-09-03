package io.oryxos.storage;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * 嵌入式 PG 单例（025 T018）：整个测试 JVM 共享一个 zonky 实例（启动 ~1.7s，避免每类重付），每个测试类 独立 database 隔离数据。JVM
 * 退出时随进程回收（zonky 自带 shutdown hook 清理数据目录）。
 */
final class PgTestSupport {

  private static EmbeddedPostgres postgres;

  private PgTestSupport() {}

  /** 为测试类建独立库并返回其 jdbc url（自带 user 参数，无需另配 username）。 */
  static synchronized String databaseUrl(Class<?> testClass) {
    if (postgres == null) {
      try {
        postgres = EmbeddedPostgres.start();
      } catch (IOException e) {
        throw new UncheckedIOException("embedded postgres failed to start", e);
      }
    }
    String dbName = "t_" + testClass.getSimpleName().toLowerCase(Locale.ROOT);
    try (Connection connection = postgres.getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + dbName);
    } catch (SQLException e) {
      // 42P04 = duplicate_database：同类重入（如 JUnit 重跑）复用既有库
      if (!"42P04".equals(e.getSQLState())) {
        throw new IllegalStateException("failed to create test database " + dbName, e);
      }
    }
    return "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + dbName + "?user=postgres";
  }
}
