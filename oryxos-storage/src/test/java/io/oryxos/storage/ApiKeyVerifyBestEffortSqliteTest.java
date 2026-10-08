package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 018 FR-009：「Key 校验通过后系统 MUST 更新该 Key 的最近使用时间；**更新失败 MUST NOT 阻断业务请求**」。
 *
 * <p>这条契约的难点在于它写在一个事务里：{@code @Transactional} 的 {@code verify} 让 {@code repository.save(key)} 退化成一次
 * merge，真正的 UPDATE 要等到方法返回、事务提交时才发出—— 也就是在 {@code touchLastUsed} 的 try/catch
 * 之外。于是「更新失败只记日志」不可能成立：写竞争下 一把**有效**的 Key 会让认证直接抛出去。
 *
 * <p>{@code NOT_SUPPORTED} 压制测试事务，让 {@code verify} 自己那个事务真的在方法返回时提交；否则 提交点会推迟到测试方法结束，这里就测不到真实的边界。
 */
@SqliteJpaTest
@Import(ApiKeyService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiKeyVerifyBestEffortSqliteTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }

  @Autowired private ApiKeyService service;
  @Autowired private ApiKeyRepository repository;
  @Autowired private DataSource dataSource;

  /** 另一条连接持着写锁不提交：此刻任何写事务都会拿到 SQLITE_BUSY。 */
  @Test
  @DisplayName("verify：治理写入拿不到写锁时，有效 Key 仍必须通过认证")
  void verifySucceedsWhileTheLastUsedWriteIsBlocked() throws Exception {
    ApiKeyService.CreatedKey created = service.create("ci-bot-contention");

    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (Statement statement = blocker.createStatement()) {
        statement.executeUpdate("UPDATE api_keys SET name = name"); // 取写锁，保持不提交
      }

      // 这次调用里 last_used_at 的 UPDATE 必然失败——但失败的是治理信号，不是认证结论
      assertTrue(service.verify(created.plaintext()), "治理写入失败不得把有效 Key 的请求打挂（018 FR-009）");
    }
  }

  @Test
  @DisplayName("反向：没有写竞争时 last_used_at 照常落库")
  void verifyStillPersistsLastUsedWithoutContention() {
    ApiKeyService.CreatedKey created = service.create("ci-bot-clean");

    assertTrue(service.verify(created.plaintext()));

    ApiKey reloaded = repository.findByKeyHash(sha256Hex(created.plaintext())).orElseThrow();
    assertNotNull(reloaded.getLastUsedAt(), "无竞争时 last_used_at 必须写进去");
  }

  private static String sha256Hex(String plaintext) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
