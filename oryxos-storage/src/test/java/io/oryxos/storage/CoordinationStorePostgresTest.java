package io.oryxos.storage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** CoordinationStore 契约用例的 PostgreSQL 档实跑（026：同套用例两库全绿）。 */
@PostgresJpaTest
class CoordinationStorePostgresTest extends CoordinationStoreContractTest {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(CoordinationStorePostgresTest.class));
  }
}
