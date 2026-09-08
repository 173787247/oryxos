package io.oryxos.storage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** ToolInvocationRepository 契约用例的 PostgreSQL 档实跑（025 T018：同套用例两库全绿，SC-005）。 */
@PostgresJpaTest
class ToolInvocationRepositoryPostgresTest extends ToolInvocationRepositoryContractTest {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(ToolInvocationRepositoryPostgresTest.class));
  }
}
