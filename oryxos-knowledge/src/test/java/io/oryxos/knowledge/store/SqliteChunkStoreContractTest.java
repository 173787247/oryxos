package io.oryxos.knowledge.store;

import io.oryxos.storage.KnowledgeChunkRepository;
import io.oryxos.storage.KnowledgeDocumentRepository;
import io.oryxos.storage.migration.SqliteMigrationsConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 默认生产档（{@code knowledge.store=sqlite}）实跑：与内存档同套用例（{@link ChunkStoreContractTest}）。
 *
 * <p>基座与 {@code oryxos-storage} 的 {@code SqliteJpaTest} 同款：{@code @DataJpaTest} + Flyway V1 基线建表， 另
 * {@code @Import} 存量收敛迁移配置。本模块拿不到它的测试构件（未发布 test-jar），故按同一口径就地声明。
 */
@DataJpaTest
@AutoConfigureDataSourceInitialization
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SqliteMigrationsConfiguration.class)
@TestPropertySource(
    properties = {
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.flyway.locations=classpath:db/migration/sqlite",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0"
    })
class SqliteChunkStoreContractTest extends ChunkStoreContractTest {

  @TempDir static Path dbDir;

  @Autowired private KnowledgeDocumentRepository documents;
  @Autowired private KnowledgeChunkRepository chunks;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("chunk-store-contract.db"));
  }

  @Override
  protected ChunkStore newStore() {
    return new SqliteChunkStore(documents, chunks);
  }

  /**
   * 用例之间清库隔离。契约用例声明在抽象基类，而类级 {@code @Transactional}（{@code @DataJpaTest} 带来的）
   * 只对声明在本类里的方法生效——继承来的用例实测不在事务里，写一行提交一行，所以必须自己清。
   */
  @BeforeEach
  void clearTables() {
    chunks.deleteAll();
    documents.deleteAll();
  }

  /** 分叉（需维护者定夺）：带 id 的保存按更新处理，未知 id 直接拒绝。 */
  @Override
  protected boolean rejectsUnknownDocumentId() {
    return true;
  }
}
