package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.ApiKeyService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 018 端到端：真实 HTTP + SQLite + filter 注册链路上验证 API Key 门禁（quickstart V2~V4 主路径）——生成 Key（明文仅
 * 返回一次、库中只有哈希）→ 无 Key/错 Key 401 → Bearer 与 X-API-Key 双写法 200 → 吊销即时生效且第二把 Key 不受影响 → health
 * 豁免。管理台认证保持关闭（012 AuthStartupCheck 会因无账号 fail-fast），session 互认由 ApiKeyAuthFilterTest 单测覆盖。无
 * key、无网络、gate 内可跑。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock", "oryxos.web.apikey.enabled=true"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiKeyAuthE2ETest {

  private static final Path ROOT = seedWorkspace();

  @Autowired private TestRestTemplate rest;
  @Autowired private ApiKeyService apiKeyService;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-apikey-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents"));
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("apikey-e2e.db"));
  }

  @Test
  @Order(1)
  void healthExempt_noCredentials_ok() {
    ResponseEntity<String> response = rest.getForEntity("/api/v1/health", String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  @Order(2)
  void noKey_rejected401_withChallenge() {
    ResponseEntity<String> response = rest.getForEntity("/api/v1/profiles", String.class);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertEquals("Bearer realm=\"OryxOS\"", response.getHeaders().getFirst("WWW-Authenticate"));
    assertTrue(response.getBody() != null && response.getBody().contains("\"code\":401"));
  }

  @Test
  @Order(3)
  void wrongKey_rejected401() {
    ResponseEntity<String> response =
        exchange("/api/v1/profiles", "X-API-Key", "oryx_" + "x".repeat(42));
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
  }

  @Test
  @Order(4)
  void createdKey_bothHeaderStyles_ok_thenRevokeImmediate() {
    ApiKeyService.CreatedKey first = apiKeyService.create("e2e-ci");
    ApiKeyService.CreatedKey second = apiKeyService.create("e2e-report");

    // 明文仅返回一次；实体上只有哈希与前缀
    assertTrue(first.plaintext().startsWith("oryx_"));
    assertFalse(first.key().getKeyHash().contains(first.plaintext()));

    // 双写法等效（FR-003）
    assertEquals(
        HttpStatus.OK,
        exchange("/api/v1/profiles", "X-API-Key", first.plaintext()).getStatusCode());
    assertEquals(
        HttpStatus.OK,
        exchange("/api/v1/profiles", "Authorization", "Bearer " + first.plaintext())
            .getStatusCode());

    // 吊销即时生效（SC-004），另一把 Key 不受影响
    assertTrue(apiKeyService.revoke("e2e-ci"));
    assertEquals(
        HttpStatus.UNAUTHORIZED,
        exchange("/api/v1/profiles", "X-API-Key", first.plaintext()).getStatusCode());
    assertEquals(
        HttpStatus.OK,
        exchange("/api/v1/profiles", "X-API-Key", second.plaintext()).getStatusCode());
  }

  private ResponseEntity<String> exchange(String path, String headerName, String headerValue) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(headerName, headerValue);
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
