package io.oryxos.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.security.ApiKeyAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * ApiKeyAuthFilter 注册（018-rest-api-key）。
 *
 * <p>{@code addUrlPatterns("/api/v1/*", "/api/v2/*")} 精确限定只拦 REST API（含 v2 调度端点，新增 API
 * 版本时必须同步在此登记，否则该版本整棵子树匿名可达）；与 012 {@code AuthFilterConfig} 的 {@code /admin/*} 模式互不重叠，{@code
 * /admin/**} 与静态资源天然不受影响（FR-002）。豁免路径（health/auth 子树/OPTIONS） 在 filter 内部判定（{@link
 * ApiKeyAuthFilter}）。
 */
@Configuration
public class ApiKeyFilterConfig {

  /** 受 API Key 门禁保护的 REST API 版本前缀；新增版本必须在此登记。 */
  static final String[] PROTECTED_URL_PATTERNS = {"/api/v1/*", "/api/v2/*"};

  @Bean
  FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
      ApiKeyService apiKeyService,
      WebSessionService sessionService,
      WebApiKeyProperties properties,
      ObjectMapper objectMapper) {
    FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(
        new ApiKeyAuthFilter(apiKeyService, sessionService, properties, objectMapper));
    registration.addUrlPatterns(PROTECTED_URL_PATTERNS);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
    return registration;
  }
}
