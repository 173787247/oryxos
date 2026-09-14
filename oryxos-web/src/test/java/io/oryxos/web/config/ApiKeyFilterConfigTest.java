package io.oryxos.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.security.ApiKeyAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * 钉死 ApiKeyAuthFilter 的 URL 注册模式：所有 REST API 版本前缀与 Actuator 都必须在 {@code
 * ApiKeyFilterConfig.PROTECTED_URL_PATTERNS} 登记。曾遗漏 {@code /api/v2/*}——v2 调度端点（含立即触发 Agent 的 {@code
 * POST /api/v2/schedules/{id}/run}）在 apikey.enabled=true 时仍匿名可达；{@code /actuator/*} 漏挂同理
 * （prometheus/metrics 指标匿名泄露）。
 */
class ApiKeyFilterConfigTest {

  @Test
  @DisplayName("注册模式覆盖/api/v1、/api/v2与/actuator_三棵子树都在门禁内")
  void registration_coversBothApiVersions() {
    ApiKeyFilterConfig config = new ApiKeyFilterConfig();
    // 第 5 个实参（RbacEnforcer）传 null 是刻意的：本用例钉的是「URL 注册模式」这件事实，
    // 不是装配签名；039 把授权切面接在 filter 内部、不新增 URL pattern，正是为了不触动本用例守护的这条边界。
    FilterRegistrationBean<ApiKeyAuthFilter> registration =
        config.apiKeyAuthFilter(
            mock(ApiKeyService.class),
            mock(WebSessionService.class),
            mock(WebUserService.class),
            new WebApiKeyProperties(),
            new ObjectMapper(),
            null);

    assertThat(registration.getUrlPatterns())
        .containsExactlyInAnyOrder("/api/v1/*", "/api/v2/*", "/actuator/*");
  }

  @Test
  @DisplayName("登记常量本身包含v2与actuator_防止回退")
  void protectedPatterns_containV2() {
    assertThat(ApiKeyFilterConfig.PROTECTED_URL_PATTERNS).contains("/api/v2/*", "/actuator/*");
  }
}
