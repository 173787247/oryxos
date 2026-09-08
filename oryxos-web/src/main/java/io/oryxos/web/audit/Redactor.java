package io.oryxos.web.audit;

import java.util.regex.Pattern;

/**
 * 审计展示层脱敏（021）：四类内置敏感形态 → 前 4 字符 + {@code ****} 掩码；未命中原样返回（不误伤）。
 *
 * <p>唯一脱敏实现（FR-008）：时间线与未来任何审计内容展示面统一调用本类，不各写各的。 规则内置不可配置；只作用于展示值——落库保持原文（Clarifications
 * 裁决：排障现场完整，库访问 = 运维特权边界 + 018 门禁）。
 */
public final class Redactor {

  /** API key 已知前缀 + 长随机串（sk-… / oryx_…）。 */
  private static final Pattern API_KEY = Pattern.compile("(?:sk-|oryx_)[A-Za-z0-9_-]{8,}");

  /** Authorization 头/字段的凭证段（scheme 可选；容忍转义 JSON 里的 \" 形态）。 */
  private static final Pattern AUTHORIZATION =
      Pattern.compile(
          "(?i)(authorization(?:\\\\?[\"'])?\\s*[:=]\\s*(?:\\\\?[\"'])?\\s*"
              + "(?:bearer|basic|token)?\\s*)([A-Za-z0-9._~+/=-]{6,})");

  /** URL userinfo 的口令段：scheme://user:pass@host → user:****@host。 */
  private static final Pattern URL_USERINFO =
      Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://[^/\\s:@\"']+:)([^@/\\s\"']+)(@)");

  /** 敏感字段取值（JSON / 键值对两种形态；容忍转义 JSON 里的 \" 形态——审计参数常为字符串内嵌 JSON）。 */
  private static final Pattern SENSITIVE_FIELD =
      Pattern.compile(
          "(?i)((?:\\\\?[\"'])?(?:password|passwd|secret|token|api_key|apikey|access_key)"
              + "(?:\\\\?[\"'])?\\s*[:=]\\s*(?:\\\\?[\"'])?)([^\"'\\\\,;\\s}]+)");

  private Redactor() {}

  /** 对展示值做四类形态脱敏；null / 空串原样返回。 */
  public static String redact(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String result = SENSITIVE_FIELD.matcher(value).replaceAll(m -> m.group(1) + mask(m.group(2)));
    result = AUTHORIZATION.matcher(result).replaceAll(m -> m.group(1) + mask(m.group(2)));
    result = URL_USERINFO.matcher(result).replaceAll(m -> m.group(1) + "****" + m.group(3));
    result = API_KEY.matcher(result).replaceAll(Redactor::maskMatch);
    return result;
  }

  private static String maskMatch(java.util.regex.MatchResult match) {
    return mask(match.group());
  }

  /** 前 4 字符 + ****；不足 4 字符整体掩码（防短值反推）。 */
  private static String mask(String value) {
    return value.length() <= 4 ? "****" : value.substring(0, 4) + "****";
  }
}
