package io.oryxos.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** ToolInvocationRepository 契约用例的 SQLite 档实跑（025 T018：同套用例两库全绿，SC-005）。 */
@SqliteJpaTest
class ToolInvocationRepositorySqliteTest extends ToolInvocationRepositoryContractTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }
}
