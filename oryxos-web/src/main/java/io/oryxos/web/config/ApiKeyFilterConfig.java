package io.oryxos.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.security.ApiKeyAuthFilter;
import io.oryxos.web.security.RbacEnforcer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * ApiKeyAuthFilter 注册（018-rest-api-key）。
 *
 * <p>{@code addUrlPatterns("/api/v1/*", "/api/v2/*", "/actuator/*")} 精确限定只拦 REST API（含 v2 调度端点，新增
 * API 版本时必须同步在此登记，否则该版本整棵子树匿名可达）与 Actuator 端点（prometheus/metrics/info 泄露运行时与业务指标，须认证；health 探活子树在
 * filter 内豁免）；与 012 {@code AuthFilterConfig} 的 {@code /admin/*} 模式互不重叠，{@code /admin/**}
 * 与静态资源天然不受影响（FR-002）。 豁免路径（health/auth 子树/OPTIONS） 在 filter 内部判定（{@link ApiKeyAuthFilter}）。
 */
@Configuration
public class ApiKeyFilterConfig {

  /** 受 API Key 门禁保护的 URL 前缀：REST API 各版本 + Actuator；新增版本必须在此登记。 */
  static final String[] PROTECTED_URL_PATTERNS = {"/api/v1/*", "/api/v2/*", "/actuator/*"};

  /**
   * 注册 ApiKeyAuthFilter，并注入 RBAC 强制点（039-identity-authorization）。
   *
   * <p>授权在既有 filter 内部收口，<b>不新增 URL pattern</b>：{@link #PROTECTED_URL_PATTERNS} 是手工维护数组，
   * 新增模式一旦漏登记就是整棵子树匿名可达（该数组自身 Javadoc 已记录这条风险）。因此 039 只改 filter 内部行为， 不动登记表。
   */
  @Bean
  FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
      ApiKeyService apiKeyService,
      WebSessionService sessionService,
      io.oryxos.storage.WebUserService userService,
      WebApiKeyProperties properties,
      ObjectMapper objectMapper,
      RbacEnforcer rbacEnforcer,
      io.oryxos.web.security.SessionTeamIdsCache sessionTeamIdsCache,
      io.oryxos.web.security.PrincipalTeamIdsMerger principalTeamIdsMerger) {
    FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(
        new ApiKeyAuthFilter(
            apiKeyService,
            sessionService,
            userService,
            properties,
            objectMapper,
            rbacEnforcer,
            sessionTeamIdsCache,
            principalTeamIdsMerger));
    registration.addUrlPatterns(PROTECTED_URL_PATTERNS);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
    return registration;
  }
}
