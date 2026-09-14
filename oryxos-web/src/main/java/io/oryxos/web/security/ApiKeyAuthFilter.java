package io.oryxos.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.WebApiKeyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * REST API Key 认证过滤器（018-rest-api-key）。
 *
 * <p>拦 {@code /api/v1/**}、{@code /api/v2/**} 与 {@code /actuator/**}（由 {@code ApiKeyFilterConfig} 的
 * {@code FilterRegistrationBean} 限定 URL 模式，{@code PROTECTED_URL_PATTERNS} 是唯一登记处）；与 012 的 {@code
 * BasicAuthFilter}（只拦 {@code /admin/*}）URL 模式互不重叠，两扇门各管各的（FR-002）。
 *
 * <p>豁免（filter 内判定，契约见 specs/018 contracts/auth-contract.md §1）：
 *
 * <ol>
 *   <li>HTTP {@code OPTIONS}——CORS 预检不携带自定义头（FR-014）；
 *   <li>{@code /api/v1/health}——K8s/LB 探活（FR-002）;
 *   <li>{@code /actuator/health} 及其 liveness/readiness 子路径——探活不带凭据；匿名仅见聚合状态， 组件/数据源/磁盘详情由 {@code
 *       management.endpoint.health.show-details=when-authorized} 挡住；
 *   <li>{@code /api/v1/auth/**}——012 管理台登录子树，端点自身校验（FR-002）。
 * </ol>
 *
 * <p>凭据两条路径（任一有效即放行）：
 *
 * <ol>
 *   <li>API Key——{@code Authorization: Bearer <key>} 或 {@code X-API-Key: <key>} 二选一等效（FR-003）→
 *       {@link ApiKeyService#verify}；
 *   <li>管理台 session——{@code oryxos_session} cookie → {@link WebSessionService#findValid}（FR-011，
 *       管理台 SPA 与 REST 同源，锁门不锁自己人；Clarifications Q1「session 即凭据」）。
 * </ol>
 *
 * <p>都无/都失败：统一 401 JSON（{@link ApiResponse} 信封）+ {@code WWW-Authenticate: Bearer}。无 Key/格式错/
 * 不存在/已吊销返回完全相同的响应（防探测，FR-004）；具体原因只进 DEBUG 日志且只记前缀，NEVER 记明文（宪法 VI）。
 *
 * <p>{@code apikey.enabled=false} 直接放行（默认关，SC-001 回归零破坏）。不抛异常——filter 在 DispatcherServlet
 * 之前，{@code @RestControllerAdvice} 捕不到，直接写响应（镜像 BasicAuthFilter）。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  /** Bearer scheme 前缀（RFC 6750），含尾随空格。 */
  private static final String BEARER_PREFIX = "Bearer ";

  /** 备选请求头（与项目出站 HTTP 工具的 X-API-Key 约定同名）。 */
  private static final String API_KEY_HEADER = "X-API-Key";

  /** 401 挑战头 realm（R9，与 012 默认 realm 一致）。 */
  private static final String CHALLENGE = "Bearer realm=\"OryxOS\"";

  private static final int UNAUTHORIZED_CODE = HttpStatus.UNAUTHORIZED.value();
  private static final String UNAUTHORIZED_MESSAGE = "Unauthorized";

  /** 探活豁免路径（FR-002）。 */
  private static final String HEALTH_PATH = "/api/v1/health";

  /** Actuator 探活根路径（K8s/LB 不带凭据拉取；详情由 show-details=when-authorized 限制）。 */
  private static final String ACTUATOR_HEALTH_PATH = "/actuator/health";

  /** Actuator 探活子路径前缀（/actuator/health/liveness、/readiness 等探针组）。 */
  private static final String ACTUATOR_HEALTH_PREFIX = "/actuator/health/";

  /** 012 管理台登录子树（端点自身校验，FR-002）。 */
  private static final String AUTH_SUBTREE_PREFIX = "/api/v1/auth/";

  private static final String AUTH_SUBTREE_ROOT = "/api/v1/auth";

  private final ApiKeyService apiKeyService;
  private final WebSessionService sessionService;
  private final WebApiKeyProperties properties;
  private final ObjectMapper objectMapper;

  /** RBAC 强制点（039）：{@code null} = 未启用授权切面（四参构造的既有路径）。 */
  private final RbacEnforcer rbacEnforcer;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "apiKeyService/sessionService/properties/objectMapper 均为 Spring 注入的共享单例，构造注入存同一引用正是意图"
              + "（镜像 BasicAuthFilter 的 SuppressFBWarnings 模式）。")
  public ApiKeyAuthFilter(
      ApiKeyService apiKeyService,
      WebSessionService sessionService,
      WebApiKeyProperties properties,
      ObjectMapper objectMapper) {
    this(apiKeyService, sessionService, properties, objectMapper, null);
  }

  /**
   * 带 RBAC 强制点的构造（039-identity-authorization）。
   *
   * <p>{@code rbacEnforcer} 允许为 {@code null}——既有部署与单测走四参构造时不设授权切面，行为与 018 完全一致
   * （默认关零行为变化）。装配侧（{@code ApiKeyFilterConfig}）在容器启动时始终注入真实实例。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "rbacEnforcer 为 Spring 注入的共享单例，构造注入存同一引用正是意图（同上）。")
  public ApiKeyAuthFilter(
      ApiKeyService apiKeyService,
      WebSessionService sessionService,
      WebApiKeyProperties properties,
      ObjectMapper objectMapper,
      RbacEnforcer rbacEnforcer) {
    this.apiKeyService = apiKeyService;
    this.sessionService = sessionService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.rbacEnforcer = rbacEnforcer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.isEnabled()) {
      filterChain.doFilter(request, response);
      return;
    }
    if (isExempt(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    // (1) API Key 路径（Bearer / X-API-Key 等效）
    String presented = extractKey(request);
    if (presented != null && apiKeyService.verify(presented)) {
      if (!prepareAndAuthorize(request, response, () -> apiKeyPrincipal(presented))) {
        return;
      }
      filterChain.doFilter(request, response);
      return;
    }
    // (2) 管理台 session 互认（FR-011：session 即凭据）
    Optional<String> sessionUser = sessionUsername(request);
    if (sessionUser.isPresent()) {
      String username = sessionUser.get();
      if (!prepareAndAuthorize(
          request, response, () -> Principal.user(username, username, Set.of()))) {
        return;
      }
      filterChain.doFilter(request, response);
      return;
    }
    reject(response);
  }

  /**
   * 置入主体并做授权裁决（039）。
   *
   * <p>{@code rbac.enabled=false} 时<b>连主体都不构造</b>。为此这里刻意收 {@link Supplier} 而不是 {@link
   * Principal}：Java 在进入方法前就会求值全部实参，若直接传主体对象，「构造主体」（其中包含一次库读）会在 判活之前发生，判活就形同虚设——这个坑踩过一次，注释留档。判定收敛在
   * {@link RbacEnforcer#isActive()}。
   *
   * @return {@code false} 表示已写出拒绝响应，调用方必须立即返回
   */
  private boolean prepareAndAuthorize(
      HttpServletRequest request, HttpServletResponse response, Supplier<Principal> principal)
      throws IOException {
    if (rbacEnforcer == null || !rbacEnforcer.isActive()) {
      return true;
    }
    PrincipalHolder.set(request, principal.get());
    return rbacEnforcer.authorize(request, response);
  }

  /**
   * 构造 API Key 主体：审计里只允许出现 Key 名称，绝不出现明文。
   *
   * <p>拿不到名称时回落到固定占位而不是抛异常——主体标识缺失不应把一条本已通过认证的请求打挂。
   */
  private Principal apiKeyPrincipal(String presented) {
    String name = apiKeyService.findNameByPlaintext(presented);
    String id = name == null || name.isBlank() ? Principal.API_KEY_FALLBACK_ID : name;
    Set<Role> noRoles = Set.of();
    return Principal.apiKey(id, id, noRoles);
  }

  private static boolean isExempt(HttpServletRequest request) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }
    String uri = request.getRequestURI();
    return HEALTH_PATH.equals(uri)
        || ACTUATOR_HEALTH_PATH.equals(uri)
        || (uri != null && uri.startsWith(ACTUATOR_HEALTH_PREFIX))
        || AUTH_SUBTREE_ROOT.equals(uri)
        || (uri != null && uri.startsWith(AUTH_SUBTREE_PREFIX));
  }

  /** 提取明文 Key：Authorization Bearer 优先，X-API-Key 兜底；都无返 null。 */
  private static String extractKey(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
      String key = authorization.substring(BEARER_PREFIX.length()).strip();
      if (!key.isEmpty()) {
        return key;
      }
    }
    String header = request.getHeader(API_KEY_HEADER);
    return header == null || header.isBlank() ? null : header.strip();
  }

  /**
   * 管理台 session 互认（FR-011）：返回有效 session 的账号名，无效/无 cookie 返 {@link Optional#empty()}。
   *
   * <p>018 只关心「session 是否有效」（布尔），039 需要主体标识（是谁），因此把塌缩掉的用户名还原出来。 判定条件与 018 完全一致，只是不再丢弃账号名。
   */
  private Optional<String> sessionUsername(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(c -> BasicAuthFilter.SESSION_COOKIE.equals(c.getName()))
        .map(Cookie::getValue)
        .filter(v -> v != null && !v.isBlank())
        .findFirst()
        .flatMap(sessionService::findValid)
        .map(session -> session.getUsername());
  }

  /** 统一 401：所有失败原因同一响应（防探测，FR-004）。 */
  private void reject(HttpServletResponse response) throws IOException {
    response.setStatus(UNAUTHORIZED_CODE);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            objectMapper.writeValueAsString(
                ApiResponse.error(UNAUTHORIZED_CODE, UNAUTHORIZED_MESSAGE)));
  }
}
