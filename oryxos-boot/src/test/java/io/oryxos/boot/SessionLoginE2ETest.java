package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.WebUserService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * {@code oryxos.web.auth.enabled=true} 的**启动**路径，以及管理台会话登录的完整链路（012-web-auth US3）。
 *
 * <p>为什么需要它：全仓此前只有 {@code WebAuthPropertiesTest}（{@code ApplicationContextRunner}，不启动服务）与 {@code
 * AuthApiControllerTest}/{@code BasicAuthFilterTest}（standalone MockMvc + mock 服务）覆盖认证。 {@code
 * ApiKeyAuthE2ETest} 虽然发过真实登录请求，但它是**运行时**才把 {@code WebAuthProperties} 拧开、且同时开了 {@code
 * apikey.enabled}。于是"启动时开关就为 true"这条装配链——{@code AuthStartupCheck} 在没有任何账号时 fail-fast
 * 之前要先能播种账号、{@code BasicAuthFilter} 在真实容器里是否真的按 {@code /admin/*} 注册——从没被跑过。开关型/装配型 功能只要没有真的启动一次，坏了
 * CI 也绿（#978/#980 同类缺口）。
 *
 * <p>这里启动整台服务（真实 SQLite/Flyway/filter 注册链/HTTP），断言：服务能起来、登录发放 HttpOnly 会话 cookie、该 cookie 能过管理台
 * filter 与 REST 会话门、匿名/伪造/错误密码/登出后都被拒、且 {@code /api/v1/**} 不被管理台认证误伤 （SC-003）。
 *
 * <p>其中 {@code /admin/}、{@code /admin/login} 的 200 由打进包里的 SPA 静态资源提供（{@code oryxos-web} 的
 * generate-resources 构建；{@code -Dfrontend.skip=true} 的纯 Java 构建里没有这个产物）。标准构建（CI 与本仓的全量 gate）都会构建它。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = {OryxOsRuntime.class, SessionLoginE2ETest.SeedConsoleUser.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock", "oryxos.web.auth.enabled=true"})
class SessionLoginE2ETest {

  private static final Path ROOT = tempWorkspace();
  private static final String USERNAME = "console-admin";
  private static final String PASSWORD = "console-pass-123";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  /** 无 cookie 容器的客户端（匿名请求专用；见 {@link #anonymousClient()}）。 */
  private final RestTemplate anonymous = anonymousClient();

  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) {
    registry.add("oryxos.root", ROOT::toString);
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("session-login.db"));
  }

  @Test
  @DisplayName("auth.enabled=true 时整台服务能启动")
  void bootsWithAuthEnabled() {
    ResponseEntity<String> health = getAnonymous("/api/v1/health");

    assertThat(health.getStatusCode())
        .as("服务在响应；能走到这一步同时说明 AuthStartupCheck 没有拒绝启动")
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("登录成功：200 + oryxos_session 会话 cookie（HttpOnly/SameSite=Strict/Path=/）")
  void loginSuccessIssuesSessionCookie() throws Exception {
    ResponseEntity<String> login = login(USERNAME, PASSWORD);

    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = mapper.readTree(login.getBody());
    assertThat(body.get("code").asInt()).isZero();
    assertThat(body.at("/data/authenticationEnabled").asBoolean()).isTrue();
    assertThat(body.at("/data/username").asText()).isEqualTo(USERNAME);

    String setCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).as("登录必须发放会话 cookie").isNotNull();
    assertThat(setCookie)
        .as("012 FR-013 的 cookie 形状：oryxos_session、作用域 /、HttpOnly、SameSite=Strict")
        .startsWith("oryxos_session=")
        .contains("Path=/", "HttpOnly", "SameSite=Strict");
    assertThat(setCookie)
        .as("登录 cookie 是会话 cookie（maxAge=-1 不应序列化成 Max-Age，否则浏览器会立刻丢弃）")
        .doesNotContain("Max-Age");
  }

  @Test
  @DisplayName("会话门：带 cookie 的 /api/v1/auth/me 200，匿名 401")
  void sessionCookieOpensTheSessionGate() throws Exception {
    ResponseEntity<String> anonymous = getAnonymous("/api/v1/auth/me");
    assertThat(anonymous.getStatusCode())
        .as("认证开启且无 cookie：/me 自己校验 session，必须 401")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(mapper.readTree(anonymous.getBody()).get("code").asInt()).isEqualTo(401);

    ResponseEntity<String> authenticated = get("/api/v1/auth/me", sessionCookie());
    assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode me = mapper.readTree(authenticated.getBody()).at("/data");
    assertThat(me.get("authenticationEnabled").asBoolean()).isTrue();
    assertThat(me.get("username").asText()).isEqualTo(USERNAME);
    assertThat(rolesOf(me))
        .as("角色来自 WebUserService.rolesOf（新账号默认 VIEWER）")
        .containsExactly("VIEWER");
  }

  @Test
  @DisplayName("管理台 filter：匿名 401+Basic 挑战；伪造 cookie 仍 401；/admin/login 放行")
  void adminConsoleFilterRejectsAnonymousAndForgedSessions() throws Exception {
    ResponseEntity<String> anonymous = getAnonymous("/admin/");
    assertThat(anonymous.getStatusCode())
        .as("未认证访问 /admin/**：filter 在 DispatcherServlet 之前就拒绝，而不是落到 SPA/404")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(anonymous.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .as("curl/自动化要拿到 Basic 挑战（realm 来自 oryxos.web.auth.realm 默认值）")
        .isEqualTo("Basic realm=\"OryxOS\"");
    assertThat(mapper.readTree(anonymous.getBody()).get("code").asInt()).isEqualTo(401);

    ResponseEntity<String> forged = get("/admin/", "oryxos_session=forged-session-id");
    assertThat(forged.getStatusCode())
        .as("库里不存在的 session id 不能被放行")
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<String> loginPage = getAnonymous("/admin/login");
    assertThat(loginPage.getStatusCode())
        .as("登录页自身必须豁免（FR-017），否则任何人都登不进来")
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("管理台 filter：登录拿到的会话 cookie 能进 SPA")
  void adminConsoleAcceptsTheSessionCookie() {
    ResponseEntity<String> admin = get("/admin/", sessionCookie());

    assertThat(admin.getStatusCode())
        .as("有效会话应穿过 filter 落到 SPA 入口（index.html）")
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("密码错：401 统一信封，且不发放会话 cookie")
  void wrongPasswordIsRejectedWithoutSession() throws Exception {
    ResponseEntity<String> login = login(USERNAME, "definitely-not-the-password");

    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(login.getHeaders().containsHeader(HttpHeaders.SET_COOKIE))
        .as("失败登录不能发会话 cookie")
        .isFalse();
    JsonNode body = mapper.readTree(login.getBody());
    assertThat(body.get("code").asInt()).isEqualTo(401);
    assertThat(body.get("message").asText())
        .as("不区分用户不存在/密码错，防用户名枚举")
        .isEqualTo("Invalid username or password");
  }

  @Test
  @DisplayName("登出：清 cookie + 删会话行，原 cookie 对 /me 与 /admin/ 都失效")
  void logoutRevokesSessionForBothGates() {
    String cookie = sessionCookie();

    ResponseEntity<String> logout = post("/api/v1/auth/logout", cookie);
    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
    String clearCookie = logout.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(clearCookie)
        .as("登出要清 cookie（空值 + Max-Age=0）")
        .startsWith("oryxos_session=")
        .contains("Max-Age=0");

    assertThat(get("/api/v1/auth/me", cookie).getStatusCode())
        .as("登出删了 session 行：旧 cookie 不能再过 /me")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/admin/", cookie).getStatusCode())
        .as("旧 cookie 也不能再穿过管理台 filter")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("管理台认证不误伤 REST（SC-003）：匿名 /api/v1/** 照常")
  void restApiIsNotGatedByAdminConsoleAuth() {
    assertThat(getAnonymous("/api/v1/health").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getAnonymous("/api/v1/profiles").getStatusCode())
        .as("/admin/** 的 filter 只拦管理台；REST 由各自的门禁把守（本用例未开 apikey）")
        .isEqualTo(HttpStatus.OK);
  }

  /** 按 012 契约发登录请求：JSON {@code {username, password}}，成功 200 + Set-Cookie。 */
  private ResponseEntity<String> login(String username, String password) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
  }

  /** 真实登录一次，取出 Cookie 请求头（{@code oryxos_session=<uuid>}，不含属性）。 */
  private String sessionCookie() {
    ResponseEntity<String> login = login(USERNAME, PASSWORD);
    assertThat(login.getStatusCode()).as("前置：登录成功才拿得到会话").isEqualTo(HttpStatus.OK);
    String setCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).isNotNull();
    return setCookie.split(";", 2)[0];
  }

  /**
   * 建一个不携带任何 cookie 的客户端：共享的 {@code TestRestTemplate} 底层是 HttpComponents，按默认 cookie 策略会把 之前登录拿到的
   * {@code oryxos_session} 自动带上——那样"匿名"请求就不再匿名（本类第一版就栽在这里：单独跑是 401，整套跑 变 200）。匿名请求一律走这里，带 cookie
   * 的请求显式塞 Cookie 头。
   */
  private static RestTemplate anonymousClient() {
    RestTemplate client = new RestTemplate(new SimpleClientHttpRequestFactory());
    client.setErrorHandler(new NoOpResponseErrorHandler());
    return client;
  }

  private ResponseEntity<String> getAnonymous(String path) {
    return anonymous.getForEntity(rest.getRootUri() + path, String.class);
  }

  private ResponseEntity<String> get(String path, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.COOKIE, cookie);
    return anonymous.exchange(
        rest.getRootUri() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private ResponseEntity<String> post(String path, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.COOKIE, cookie);
    return anonymous.exchange(
        rest.getRootUri() + path, HttpMethod.POST, new HttpEntity<>(headers), String.class);
  }

  private static List<String> rolesOf(JsonNode me) {
    List<String> roles = new ArrayList<>();
    for (JsonNode role : me.get("roles")) {
      roles.add(role.asText());
    }
    return roles;
  }

  /**
   * 在 {@code AuthStartupCheck} 之前播种账号：{@code auth.enabled=true} 但库里没有 enabled 账号时它会 fail-fast 拒绝启动
   * （FR-006），那是 {@code AuthStartupCheck} 的另一半语义。两个 {@code ApplicationRunner} 都没顺序时谁先跑取决于 bean
   * 定义顺序，所以这里必须显式 {@link Ordered#HIGHEST_PRECEDENCE}。
   */
  @TestConfiguration
  static class SeedConsoleUser {

    @Bean
    ApplicationRunner seedConsoleUser(WebUserService users) {
      return new SeedRunner(users);
    }
  }

  static final class SeedRunner implements ApplicationRunner, Ordered {

    private final WebUserService users;

    SeedRunner(WebUserService users) {
      this.users = users;
    }

    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) {
      if (!users.hasEnabledAccount()) {
        users.create(USERNAME, PASSWORD);
      }
    }
  }

  private static Path tempWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-session-login");
      Files.createDirectories(root.resolve("memory"));
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
