package io.oryxos.storage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 审批审计列宽契约的 PostgreSQL 档实跑——宽度真的会被拒绝的那一档。 */
@PostgresJpaTest
class ApprovalAuditTruncationPostgresTest extends ApprovalAuditTruncationContractTest {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(ApprovalAuditTruncationPostgresTest.class));
  }
}
