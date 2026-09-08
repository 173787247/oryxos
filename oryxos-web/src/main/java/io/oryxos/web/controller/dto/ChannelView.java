package io.oryxos.web.controller.dto;

import io.oryxos.core.channel.ChannelConfig;
import java.util.Map;

/** 渠道配置视图（017）：出参 appSecret 永不回显明文——${ENV} 字面量原样保留（不含敏感），明文值以 ****** 掩码。 */
public record ChannelView(
    String name,
    String type,
    String appId,
    String appSecret,
    String agent,
    boolean enabled,
    Map<String, String> extra) {

  private static final String MASK = "******";

  public static ChannelView from(ChannelConfig c) {
    return new ChannelView(
        c.name(),
        c.type(),
        c.appId(),
        mask(c.appSecret()),
        c.agent(),
        c.enabled(),
        maskExtra(c.extra()));
  }

  /** ${} 占位保留（引导用户走环境变量）；其余一律掩码。 */
  private static String mask(String secret) {
    if (secret == null || secret.isBlank()) {
      return secret;
    }
    return secret.contains("${") ? secret : MASK;
  }

  private static Map<String, String> maskExtra(Map<String, String> extra) {
    if (extra == null || extra.isEmpty()) {
      return Map.of();
    }
    java.util.LinkedHashMap<String, String> masked = new java.util.LinkedHashMap<>();
    extra.forEach((key, value) -> masked.put(key, mask(value)));
    return Map.copyOf(masked);
  }

  public ChannelConfig toConfig() {
    return new ChannelConfig(
        name, type, appId, appSecret, agent, enabled, extra == null ? Map.of() : extra);
  }
}
