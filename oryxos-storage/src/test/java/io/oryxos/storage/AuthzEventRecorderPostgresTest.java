package io.oryxos.storage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AuthzEventRecorder 契约用例的 PostgreSQL 档实跑（025 T018：同套用例两库全绿，SC-005）。
 *
 * <p>这一档是缺陷的复现现场：只有 PostgreSQL 真的执行 {@code VARCHAR(255)} 长度约束，超长 resourceId 的 INSERT 会被 {@code
 * value too long for type character varying(255)} 拒绝，异常再由 recorder 的 fail-open catch 吞掉。
 */
@PostgresJpaTest
class AuthzEventRecorderPostgresTest extends AuthzEventRecorderContractTest {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(AuthzEventRecorderPostgresTest.class));
  }
}
