package io.oryxos.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 团队 HTTP API 开关（#546）：默认关；关闭时 teams / user-teams 端点抛 404，与版本历史 API 同口径。
 *
 * <p>开启后仍走既有 {@code RequestActionResolver} → {@code MANAGE_MEMBERS}（ADMIN 专属；API Key 上限不含此项）。
 */
@ConfigurationProperties(prefix = "oryxos.web.teams-api")
public class WebTeamsApiProperties {

  /** 是否暴露 teams HTTP API。默认关。 */
  private boolean enabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
