package io.oryxos.core.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批策略配置快照（042 / #464）：由 {@link ApprovalPolicyProperties} 或测试直接构造；纯数据、无 Spring 依赖。
 *
 * @param enabled 总开关；false 时评估恒 ALLOW
 * @param policyVersion 策略版本字符串（审计）
 * @param defaultTtlSeconds 规则未写 ttl 时的默认有效期
 * @param defaultApprovers 规则未写 approvers 时的默认审批人
 * @param rules 有序规则；先匹配先生效
 */
public record ApprovalPolicyConfig(
    boolean enabled,
    String policyVersion,
    int defaultTtlSeconds,
    List<String> defaultApprovers,
    List<Rule> rules) {

  private static final String WILDCARD_ALL = "*";
  private static final String MCP_SERVER_WILDCARD_SUFFIX = ":*";
  private static final String EFFECT_DENY = "DENY";
  private static final String EFFECT_REJECT = "REJECT";
  private static final String SEM_TIMEOUT = "TIMEOUT";
  private static final String SEM_TIMEOUT_DENY = "TIMEOUT_DENY";

  public ApprovalPolicyConfig {
    policyVersion = policyVersion == null || policyVersion.isBlank() ? "1" : policyVersion.trim();
    defaultApprovers = defaultApprovers == null ? List.of() : List.copyOf(defaultApprovers);
    rules = rules == null ? List.of() : List.copyOf(rules);
    if (defaultTtlSeconds <= 0) {
      defaultTtlSeconds = 3600;
    }
  }

  public static ApprovalPolicyConfig disabled() {
    return new ApprovalPolicyConfig(false, "1", 3600, List.of(), List.of());
  }

  /**
   * 单条规则。
   *
   * @param id 稳定 id（审计）
   * @param agents Agent 名或 {@code *}；空 = 全部
   * @param tools 工具精确名或 MCP {@code server:*} 通配；空 = 不按工具名约束
   * @param actionTypes 动作类型；空 = 不按类型约束
   * @param approvers 审批人；空则用配置默认
   * @param ttlSeconds 有效期；null 用默认
   * @param effect {@link ApprovalOutcome#REQUIRE_APPROVAL} 或 {@link ApprovalOutcome#DENY}
   * @param onTimeout 超时语义（契约字段）
   * @param onDeny 拒绝语义（契约字段）
   */
  public record Rule(
      String id,
      List<String> agents,
      List<String> tools,
      List<HighRiskActionType> actionTypes,
      List<String> approvers,
      Integer ttlSeconds,
      ApprovalOutcome effect,
      ApprovalDenySemantics onTimeout,
      ApprovalDenySemantics onDeny) {

    public Rule {
      id = id == null || id.isBlank() ? "unnamed" : id.trim();
      agents = agents == null ? List.of() : List.copyOf(agents);
      tools = tools == null ? List.of() : List.copyOf(tools);
      actionTypes = actionTypes == null ? List.of() : List.copyOf(actionTypes);
      approvers = approvers == null ? List.of() : List.copyOf(approvers);
      effect = effect == null ? ApprovalOutcome.REQUIRE_APPROVAL : effect;
      if (effect == ApprovalOutcome.ALLOW) {
        effect = ApprovalOutcome.REQUIRE_APPROVAL;
      }
      onTimeout = onTimeout == null ? ApprovalDenySemantics.TIMEOUT_REJECT : onTimeout;
      onDeny = onDeny == null ? ApprovalDenySemantics.REJECT : onDeny;
    }

    boolean matchesAgent(String agentName) {
      if (agents.isEmpty()) {
        return true;
      }
      String agent = agentName == null ? "" : agentName;
      for (String pattern : agents) {
        if (matchesName(pattern, agent)) {
          return true;
        }
      }
      return false;
    }

    boolean matchesTool(String toolName, String mcpServer) {
      if (tools.isEmpty()) {
        return true;
      }
      for (String pattern : tools) {
        if (matchesToolPattern(pattern, toolName, mcpServer)) {
          return true;
        }
      }
      return false;
    }

    boolean matchesActionType(HighRiskActionType type) {
      if (actionTypes.isEmpty()) {
        return true;
      }
      return type != null && actionTypes.contains(type);
    }

    /** 工具约束与动作类型至少一侧有条件，否则规则无效（避免空规则误伤全场）。 */
    boolean hasSelector() {
      return !tools.isEmpty() || !actionTypes.isEmpty();
    }
  }

  static boolean matchesName(String pattern, String value) {
    if (pattern == null || pattern.isBlank() || WILDCARD_ALL.equals(pattern.trim())) {
      return true;
    }
    return asciiEqualsIgnoreCase(pattern.trim(), value);
  }

  static boolean matchesToolPattern(String pattern, String toolName, String mcpServer) {
    if (pattern == null || pattern.isBlank() || WILDCARD_ALL.equals(pattern.trim())) {
      return true;
    }
    String p = pattern.trim();
    if (p.endsWith(MCP_SERVER_WILDCARD_SUFFIX)) {
      String server = p.substring(0, p.length() - MCP_SERVER_WILDCARD_SUFFIX.length());
      return mcpServer != null && asciiEqualsIgnoreCase(server, mcpServer);
    }
    return asciiEqualsIgnoreCase(p, toolName);
  }

  /** ASCII-only case fold — mirrors InboundMessageService (SpotBugs IMPROPER_UNICODE). */
  static boolean asciiEqualsIgnoreCase(String left, String right) {
    if (left == null || right == null) {
      return left == null && right == null;
    }
    if (left.length() != right.length()) {
      return false;
    }
    for (int i = 0; i < left.length(); i++) {
      char a = left.charAt(i);
      char b = right.charAt(i);
      if (a >= 'A' && a <= 'Z') {
        a = (char) (a + ('a' - 'A'));
      }
      if (b >= 'A' && b <= 'Z') {
        b = (char) (b + ('a' - 'A'));
      }
      if (a != b) {
        return false;
      }
    }
    return true;
  }

  static String asciiUpperSnake(String raw) {
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '-') {
        out.append('_');
      } else if (c >= 'a' && c <= 'z') {
        out.append((char) (c - ('a' - 'A')));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** 从 Spring properties 转换时用的可变构建辅助。 */
  public static final class Builder {
    private boolean enabled;
    private String policyVersion = "1";
    private int defaultTtlSeconds = 3600;
    private List<String> defaultApprovers = List.of();
    private final List<Rule> rules = new ArrayList<>();

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder policyVersion(String policyVersion) {
      this.policyVersion = policyVersion;
      return this;
    }

    public Builder defaultTtlSeconds(int defaultTtlSeconds) {
      this.defaultTtlSeconds = defaultTtlSeconds;
      return this;
    }

    public Builder defaultApprovers(List<String> defaultApprovers) {
      this.defaultApprovers = defaultApprovers == null ? List.of() : List.copyOf(defaultApprovers);
      return this;
    }

    public Builder addRule(Rule rule) {
      this.rules.add(rule);
      return this;
    }

    public ApprovalPolicyConfig build() {
      return new ApprovalPolicyConfig(
          enabled, policyVersion, defaultTtlSeconds, defaultApprovers, rules);
    }
  }

  /** 解析 effect 字符串（配置友好）。 */
  public static ApprovalOutcome parseEffect(String raw) {
    if (raw == null || raw.isBlank()) {
      return ApprovalOutcome.REQUIRE_APPROVAL;
    }
    String v = asciiUpperSnake(raw.trim());
    if (EFFECT_DENY.equals(v) || EFFECT_REJECT.equals(v)) {
      return ApprovalOutcome.DENY;
    }
    return ApprovalOutcome.REQUIRE_APPROVAL;
  }

  public static ApprovalDenySemantics parseDenySemantics(
      String raw, ApprovalDenySemantics fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    String v = asciiUpperSnake(raw.trim());
    try {
      return ApprovalDenySemantics.valueOf(v);
    } catch (IllegalArgumentException ex) {
      if (EFFECT_DENY.equals(v) || EFFECT_REJECT.equals(v)) {
        return ApprovalDenySemantics.REJECT;
      }
      if (SEM_TIMEOUT.equals(v) || SEM_TIMEOUT_DENY.equals(v)) {
        return ApprovalDenySemantics.TIMEOUT_REJECT;
      }
      return fallback;
    }
  }
}
