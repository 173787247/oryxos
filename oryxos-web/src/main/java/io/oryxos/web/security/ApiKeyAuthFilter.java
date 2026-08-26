package io.oryxos.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * REST API Key 认证过滤器（018-rest-api-key）。
 *
 * <p>仅拦 {@code /api/v1/**}（由 {@code ApiKeyFilterConfig} 的 {@code FilterRegistrationBean} 限定 URL
 * 模式）；与 012 的 {@code BasicAuthFilter}（只拦 {@code /admin/*}）URL 模式互不重叠，两扇门各管各的（FR-002）。
 *
 * <p>豁免（filter 内判定，契约见 specs/018 contracts/auth-contract.md §1）：
 *
 * <ol>
 *   <li>HTTP {@code OPTIONS}——CORS 预检不携带自定义头（FR-014）；
 *   <li>{@code /api/v1/health}——K8s/LB 探活（FR-002）;
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

  /** 012 管理台登录子树（端点自身校验，FR-002）。 */
  private static final String AUTH_SUBTREE_PREFIX = "/api/v1/auth/";

  private static final String AUTH_SUBTREE_ROOT = "/api/v1/auth";

  private final ApiKeyService apiKeyService;
  private final WebSessionService sessionService;
  private final WebApiKeyProperties properties;
  private final ObjectMapper objectMapper;

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
    this.apiKeyService = apiKeyService;
    this.sessionService = sessionService;
    this.properties = properties;
    this.objectMapper = objectMapper;
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
      filterChain.doFilter(request, response);
      return;
    }
    // (2) 管理台 session 互认（FR-011：session 即凭据）
    if (authenticatedBySession(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    reject(response);
  }

  private static boolean isExempt(HttpServletRequest request) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }
    String uri = request.getRequestURI();
    return HEALTH_PATH.equals(uri)
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

  private boolean authenticatedBySession(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    return Arrays.stream(cookies)
        .filter(c -> BasicAuthFilter.SESSION_COOKIE.equals(c.getName()))
        .map(Cookie::getValue)
        .filter(v -> v != null && !v.isBlank())
        .findFirst()
        .flatMap(sessionService::findValid)
        .isPresent();
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
