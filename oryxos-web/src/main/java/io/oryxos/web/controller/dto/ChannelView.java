package io.oryxos.web.controller.dto;

import io.oryxos.core.channel.ChannelConfig;

/** 渠道配置视图（017）：出参 appSecret 永不回显明文——${ENV} 字面量原样保留（不含敏感），明文值以 ****** 掩码。 */
public record ChannelView(
    String name, String type, String appId, String appSecret, String agent, boolean enabled) {

  private static final String MASK = "******";

  public static ChannelView from(ChannelConfig c) {
    return new ChannelView(
        c.name(), c.type(), c.appId(), mask(c.appSecret()), c.agent(), c.enabled());
  }

  /** ${} 占位保留（引导用户走环境变量）；其余一律掩码。 */
  private static String mask(String secret) {
    if (secret == null || secret.isBlank()) {
      return secret;
    }
    return secret.contains("${") ? secret : MASK;
  }

  public ChannelConfig toConfig() {
    return new ChannelConfig(name, type, appId, appSecret, agent, enabled);
  }
}
