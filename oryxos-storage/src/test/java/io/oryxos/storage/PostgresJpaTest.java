package io.oryxos.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * PostgreSQL 档 @DataJpaTest 基座（025 T018）：与 {@link SqliteJpaTest} 对偶——同一套契约用例在 PG 上 实跑（SC-005
 * 两库全绿）。驱动与方言由 Boot 按 url 自动推导；建表走 Flyway postgresql 目录 V1 基线。 datasource url 由各测试类经 {@link
 * PgTestSupport#databaseUrl} 提供（每类独立库）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.flyway.locations=classpath:db/migration/postgresql",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0"
    })
public @interface PostgresJpaTest {}
