package io.oryxos.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AuthzEventRecorder 契约用例的 SQLite 档实跑（025 T018：同套用例两库全绿，SC-005）。
 *
 * <p>SQLite 不校验 {@code VARCHAR(255)} 长度，这一档证明的是有界化本身生效（落库值已被截到列宽内）；列宽被数据库强制执行的失败形态 只有 PostgreSQL
 * 档能复现。
 */
@SqliteJpaTest
class AuthzEventRecorderSqliteTest extends AuthzEventRecorderContractTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }
}
