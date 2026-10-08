package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 原子态迁移契约的 PostgreSQL 档实跑（zonky 嵌入式 PG）。
 *
 * <p>交错用例在这里的失败方式是「两个调用方都成功」：READ COMMITTED 下输家的无条件 {@code UPDATE ... WHERE id=?} 等赢家 提交后照常生效，没有
 * {@code AND state = expected} 就没有冲突可判。
 */
@PostgresJpaTest
class AtomicTransitionPostgresTest extends AtomicTransitionContractTest {

  @Autowired DataSource dataSource;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(AtomicTransitionPostgresTest.class));
  }

  @Test
  void 交错用例跑在READ_COMMITTED上() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet isolation = statement.executeQuery("SHOW transaction_isolation")) {
      assertThat(isolation.next()).isTrue();
      assertThat(isolation.getString(1)).isEqualToIgnoringCase("read committed");
    }
  }
}
