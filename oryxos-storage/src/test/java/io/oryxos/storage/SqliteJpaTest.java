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
 * SQLite 档 @DataJpaTest 基座（025，T013）：收编原先每个测试类重复的五行四件套——SQLite 驱动/方言、 ddl-auto=none、建表走 Flyway V1
 * 基线（原 spring.sql.init + schema.sql 已退役）。datasource url 仍由 各测试类的 @DynamicPropertySource 按 @TempDir
 * 提供。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = {
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.flyway.locations=classpath:db/migration/sqlite",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0"
    })
public @interface SqliteJpaTest {}
