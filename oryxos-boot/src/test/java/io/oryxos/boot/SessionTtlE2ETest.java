package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionRepository;
import io.oryxos.storage.WebUserService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code oryxos.web.auth.session-ttl} 真的到达 session 行。
 *
 * <p>这条配置此前没有任何测试，而且**曾经有两个解析器**：{@code WebAuthProperties} 里一个带 {@code @DurationUnit(HOURS)}
 * 的同名字段（全仓没有调用方），与 {@code OryxOsRuntime} 的 {@code @Value("${…:12h}")} （真正生效）。两者对裸数字的解释不同（前者小时、后者按
 * Spring 标准当毫秒），字段已删除，只剩一处。
 *
 * <p>这里钉的是**行为**：把 ttl 配成 1h，登录后 {@code web_sessions} 那一行的 {@code expires_at - created_at} 就该是
 * 1h（默认是 12h，两者差得很远，断言不会含混）。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = {OryxOsRuntime.class, SessionTtlE2ETest.SeedUser.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oryxos.providers[0].name=mock",
      "oryxos.web.auth.enabled=true",
      "oryxos.web.auth.session-ttl=1h"
    })
class SessionTtlE2ETest {

  private static final Path ROOT = seedWorkspace();

  private static final String USER = "ttl-user";

  private static final String PASSWORD = "ttl-pass-123";

  private static final Duration CONFIGURED_TTL = Duration.ofHours(1);

  private static final Duration TOLERANCE = Duration.ofMinutes(2);

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  @Autowired private WebSessionRepository sessions;

  @Test
  @DisplayName("登录写入的 session 行按配置的 session-ttl 过期，而不是默认的 12h")
  void configuredTtlReachesTheSessionRow() throws Exception {
    String cookie = loginCookie();
    String sessionId = cookie.substring(cookie.indexOf('=') + 1);

    WebSession row = sessions.findBySessionId(sessionId).orElseThrow();
    Duration lifetime = Duration.between(row.getCreatedAt(), row.getExpiresAt());

    assertThat(lifetime)
        .as("session 行寿命应等于配置的 1h（默认 12h 不在容差内），实际 %s", lifetime)
        .isBetween(CONFIGURED_TTL.minus(TOLERANCE), CONFIGURED_TTL.plus(TOLERANCE));
  }

  /** 真实登录一次，取出 {@code oryxos_session=<uuid>}（不含 Cookie 属性）。 */
  private String loginCookie() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = mapper.writeValueAsString(Map.of("username", USER, "password", PASSWORD));

    ResponseEntity<String> response =
        rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).as("登录应成功").isEqualTo(HttpStatus.OK);
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).as("登录成功应下发会话 cookie").isNotNull();
    return setCookie.split(";", 2)[0];
  }

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-session-ttl");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("ttl-agent"));
      Files.writeString(
          root.resolve("agents/ttl-agent/AGENT.md"),
          """
          ---
          name: ttl-agent
          description: session ttl 自测 Agent
          identity:
            agent_name: TTL小欧
            prompt: 你是一个测试助手。
          provider:
            name: mock
            model: mock-model
          settings:
            max_iterations: 10
            max_history_turns: 20
          ---
          你是一个测试助手。
          """);
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("session-ttl.db"));
  }

  /**
   * 登录要用的账号：{@code AuthStartupCheck} 要求库内至少一个 enabled 账号才允许启动，所以必须在启动校验之前种好。
   *
   * <p>播种必须是**单例实例化期**的一部分：Spring 先实例化全部单例、再统一回调 {@code
   * SmartInitializingSingleton.afterSingletonsInstantiated()}，所以构造器里的播种一定早于启动校验。原先 {@code Ordered}
   * runner 在 refresh 之后才跑，检查提前后不再成立。
   */
  @TestConfiguration
  static class SeedUser {

    @Bean
    SeedSessionUser seedSessionUser(WebUserService users) {
      return new SeedSessionUser(users);
    }
  }

  /** 单例实例化即播种：早于所有 {@code afterSingletonsInstantiated} 回调。 */
  static final class SeedSessionUser {

    SeedSessionUser(WebUserService users) {
      if (!users.isEnabledUser(USER)) {
        users.create(USER, PASSWORD);
        users.setRoles(USER, Set.of(io.oryxos.core.auth.Role.VIEWER));
      }
    }
  }
}
