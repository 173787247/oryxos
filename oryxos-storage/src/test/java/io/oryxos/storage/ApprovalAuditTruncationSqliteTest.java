package io.oryxos.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 同一套用例的 SQLite 档实跑：SQLite 不校验 VARCHAR 宽度，所以这一档两种实现都过。 */
@SqliteJpaTest
class ApprovalAuditTruncationSqliteTest extends ApprovalAuditTruncationContractTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }
}
