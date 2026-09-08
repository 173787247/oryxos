package io.oryxos.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * REST API Key 认证配置（018-rest-api-key）。
 *
 * <p>{@code oryxos.web.apikey.enabled} 默认 {@code false}——假设内网现状不变（回归零破坏，FR-001）；置 {@code true} 后
 * {@code /api/v1/**} 启用 API Key 门禁（豁免 {@code /api/v1/health}、{@code /api/v1/auth/*}、OPTIONS 预检），
 * {@code /admin/**} 完全不受影响。与 012 的 {@code oryxos.web.auth.enabled} 相互独立。
 */
@ConfigurationProperties(prefix = "oryxos.web.apikey")
public class WebApiKeyProperties {

  /** 是否启用 REST API Key 认证。默认关：保持「假设内网」现状。 */
  private boolean enabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
