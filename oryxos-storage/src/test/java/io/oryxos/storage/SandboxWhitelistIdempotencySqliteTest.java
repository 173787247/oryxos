package io.oryxos.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 沙箱白名单幂等契约的 SQLite 档实跑（含 SQLite 方言把约束违规包成 JpaSystemException 的那条路径）。 */
@SqliteJpaTest
class SandboxWhitelistIdempotencySqliteTest extends SandboxWhitelistIdempotencyContractTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }
}
