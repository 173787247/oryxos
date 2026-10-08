package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 原子态迁移契约的 SQLite 档实跑（产品档参数：{@code journal_mode=WAL} + {@code busy_timeout=5000}，见 {@code
 * oryxos-boot/src/main/resources/application.yml}）。
 *
 * <p>并发写是本套用例的输入，因此这里不用「无 WAL、busy_timeout 取驱动默认」的测试默认档：SQLite 档的契约面正是「并发迁移输家
 * 拿到什么」，参数不同就是另一个库的行为。产品档下输家不会拿到第二个非空结果——它拿到写锁异常（读-判-写实现）或 empty（条件 UPDATE 实现）。
 */
@SqliteJpaTest
class AtomicTransitionSqliteTest extends AtomicTransitionContractTest {

  @TempDir static Path dbDir;

  @Autowired DataSource dataSource;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
    registry.add("spring.datasource.hikari.data-source-properties.journal_mode", () -> "WAL");
    registry.add("spring.datasource.hikari.data-source-properties.busy_timeout", () -> "5000");
  }

  @Test
  void 交错用例跑在产品档参数上() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      try (ResultSet journal = statement.executeQuery("PRAGMA journal_mode")) {
        assertThat(journal.next()).isTrue();
        assertThat(journal.getString(1)).isEqualToIgnoringCase("wal");
      }
      try (ResultSet busy = statement.executeQuery("PRAGMA busy_timeout")) {
        assertThat(busy.next()).isTrue();
        assertThat(busy.getInt(1)).isEqualTo(5000);
      }
    }
  }
}
