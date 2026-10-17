package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 沙箱白名单幂等契约的 PostgreSQL 档。
 *
 * <p>并发冒烟只放在这里：{@code ConcurrentMigrationIT} 抓到的是 PG 上两副本并发启动播种同一条白名单； SQLite 是单写者模型，并发写测的是锁而不是本契约。
 *
 * <p>{@code NOT_SUPPORTED} 在父类与本类各标一次：{@code @DataJpaTest} 自带（元注解）的
 * {@code @Transactional(REQUIRED)} 对本类**自己声明**的测试方法会压过父类那条，于是插入不提交、 并发线程看到的还是上一个用例提交的行（实测：{@code
 * txActive=true}，16 个线程全在快路径返回 false）。 父类那条对继承来的方法是生效的。
 */
@PostgresJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SandboxWhitelistIdempotencyPostgresTest extends SandboxWhitelistIdempotencyContractTest {

  private static final String ENTRY = "/tmp/oryxos-whitelist-race";

  @Autowired private SandboxWhitelistRepository repository;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(SandboxWhitelistIdempotencyPostgresTest.class));
  }

  @Test
  @DisplayName("两副本并发播种同一条白名单_只有一行且不抛")
  void concurrentSeedingFromTwoReplicasLeavesOneRow() throws Exception {
    repository.deleteAll();
    JpaSandboxWhitelistStore first = new JpaSandboxWhitelistStore(repository);
    JpaSandboxWhitelistStore second = new JpaSandboxWhitelistStore(repository);
    int writers = 16;
    CyclicBarrier barrier = new CyclicBarrier(writers);
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        JpaSandboxWhitelistStore store = i % 2 == 0 ? first : second;
        results.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return store.add(Category.FILE, ENTRY);
                }));
      }
      int inserted = 0;
      for (Future<Boolean> result : results) {
        if (result.get(60, TimeUnit.SECONDS)) {
          inserted++;
        }
      }
      assertThat(inserted).as("同一条白名单只该有一次真正插入").isEqualTo(1);
      assertThat(repository.findAll()).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
