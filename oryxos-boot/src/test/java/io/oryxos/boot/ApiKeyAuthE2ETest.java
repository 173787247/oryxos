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
 * 018 端到端：真实 HTTP + SQLite + filter 注册链路上验证 API Key 门禁（quickstart V2~V5 主路径）——生成 Key（明文仅
 * 返回一次、库中只有哈希）→ 无 Key/错 Key 401 → Bearer 与 X-API-Key 双写法 200 → 吊销即时生效且第二把 Key 不受影响 → health 豁免 →
 * 双认证共存：建账号后运行时开启管理台认证（{@code WebAuthProperties} 为单例、filter 每请求读 {@code
 * isEnabled()}，与生产开启态行为一致；启动时置 true 会被 012 AuthStartupCheck 无账号 fail-fast 拦下， 故账号就绪后再开），真实 HTTP 登录拿
 * {@code oryxos_session} cookie，仅凭 session 无 Key 调 REST 通过（SC-006）。 无 key、无网络、gate 内可跑。
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
  @Autowired private io.oryxos.storage.WebUserService userService;
  @Autowired private io.oryxos.web.config.WebAuthProperties authProperties;

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

  @Test
  @Order(5)
  void dualAuth_sessionInterop_consoleSessionPassesRestGate() {
    userService.create("admin", "e2e-pass-018");
    authProperties.setEnabled(true);
    try {
      // 真实 HTTP 登录（/api/v1/auth 子树豁免 Key 门禁）→ 拿 oryxos_session cookie
      HttpHeaders loginHeaders = new HttpHeaders();
      loginHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json");
      ResponseEntity<String> login =
          rest.postForEntity(
              "/api/v1/auth/login",
              new HttpEntity<>(
                  "{\"username\":\"admin\",\"password\":\"e2e-pass-018\"}", loginHeaders),
              String.class);
      assertEquals(HttpStatus.OK, login.getStatusCode());
      String setCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
      assertTrue(setCookie != null && setCookie.startsWith("oryxos_session="));
      String cookie = setCookie.split(";", 2)[0];

      // 仅凭管理台 session、无 API Key → REST 门禁放行（FR-011 / SC-006）
      assertEquals(
          HttpStatus.OK, exchange("/api/v1/profiles", HttpHeaders.COOKIE, cookie).getStatusCode());
      // 伪造 session 且无 Key → 401
      assertEquals(
          HttpStatus.UNAUTHORIZED,
          exchange("/api/v1/profiles", HttpHeaders.COOKIE, "oryxos_session=forged-id")
              .getStatusCode());
    } finally {
      authProperties.setEnabled(false);
    }
  }

  private ResponseEntity<String> exchange(String path, String headerName, String headerValue) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(headerName, headerValue);
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
