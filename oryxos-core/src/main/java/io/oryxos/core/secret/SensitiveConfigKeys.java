package io.oryxos.core.secret;

import java.util.Locale;
import java.util.Set;

/**
 * 敏感配置项名录（022）：通知渠道 config 内命中该名录的项才加密/掩码，名录外（host/port/from 等）保持明文可读。
 *
 * <p>与 021 展示层脱敏（Redactor 的敏感字段名录）同源——「什么算敏感」全系统一个答案；规则内置不可配置（021 同款裁决）。 storage
 * 加密（JpaNotifyChannelRegistry）与 web 掩码（NotifyChannelView）共用本判定。
 */
public final class SensitiveConfigKeys {

  private static final Set<String> KEYS =
      Set.of("password", "passwd", "secret", "token", "api_key", "apikey", "access_key");

  private SensitiveConfigKeys() {}

  /** 大小写不敏感判定。 */
  public static boolean isSensitive(String key) {
    return key != null && KEYS.contains(key.toLowerCase(Locale.ROOT));
  }
}
