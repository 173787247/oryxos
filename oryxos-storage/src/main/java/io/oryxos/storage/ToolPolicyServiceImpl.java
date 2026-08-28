package io.oryxos.storage;

import io.oryxos.core.policy.ToolPolicyService;
import java.util.List;
import java.util.function.Function;

/**
 * {@link ToolPolicyService} 的 SQLite/JPA 实现（020 R5/R6）：每次裁决实时读全量规则（零缓存——规则量级几十行，
 * 热更新天然「下一次即生效」），收敛固定三步：
 *
 * <ol>
 *   <li>命中 AGENT_DENY(agent) → 拒（最终收紧，例外救不回）；
 *   <li>命中 GLOBAL_DENY 且未命中 AGENT_EXEMPT(agent) → 拒；
 *   <li>其余 → 允。
 * </ol>
 *
 * <p>pattern 匹配 = 工具精确名，或 {@code server:*}（MCP server 级通配）——后者经注入的 {@code mcpOwnerLookup}
 * 判「该工具注册归属于哪个 server」（R3：MCP 工具以原始名注册、无前缀，名字前缀匹配会落空）。
 */
public class ToolPolicyServiceImpl implements ToolPolicyService {

  private static final String WILDCARD_SUFFIX = ":*";

  private final ToolPolicyRuleRepository repository;
  private final Function<String, String> mcpOwnerLookup;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 与 ownerLookup（ToolRegistry 活视图）均为运行时共享引用，存同一引用正是意图。")
  public ToolPolicyServiceImpl(
      ToolPolicyRuleRepository repository, Function<String, String> mcpOwnerLookup) {
    this.repository = repository;
    this.mcpOwnerLookup = mcpOwnerLookup;
  }

  @Override
  public PolicyDecision check(String agentName, String toolName) {
    List<ToolPolicyRule> rules = repository.findAll();
    ToolPolicyRule agentDeny =
        firstMatch(rules, ToolPolicyRule.RuleType.AGENT_DENY, agentName, toolName);
    if (agentDeny != null) {
      return PolicyDecision.denied(describe("命中该 Agent 的定向禁用规则", agentDeny));
    }
    ToolPolicyRule globalDeny =
        firstMatch(rules, ToolPolicyRule.RuleType.GLOBAL_DENY, null, toolName);
    if (globalDeny != null) {
      ToolPolicyRule exempt =
          firstMatch(rules, ToolPolicyRule.RuleType.AGENT_EXEMPT, agentName, toolName);
      if (exempt == null) {
        return PolicyDecision.denied(describe("命中全局禁用规则", globalDeny));
      }
    }
    return PolicyDecision.ALLOWED;
  }

  /** 供管理台/启动检查复用：全量规则快照。 */
  public List<ToolPolicyRule> loadAll() {
    return repository.findAll();
  }

  private ToolPolicyRule firstMatch(
      List<ToolPolicyRule> rules, ToolPolicyRule.RuleType type, String agentName, String toolName) {
    for (ToolPolicyRule rule : rules) {
      if (!type.name().equals(rule.getRuleType())) {
        continue;
      }
      if (agentName != null && !agentName.equals(rule.getAgentName())) {
        continue;
      }
      if (patternMatches(rule.getPattern(), toolName)) {
        return rule;
      }
    }
    return null;
  }

  /** 精确名，或 server 通配（经归属表判定）。 */
  private boolean patternMatches(String pattern, String toolName) {
    if (pattern == null || toolName == null) {
      return false;
    }
    if (pattern.endsWith(WILDCARD_SUFFIX)) {
      String server = pattern.substring(0, pattern.length() - WILDCARD_SUFFIX.length());
      String owner = mcpOwnerLookup.apply(toolName);
      return server.equals(owner);
    }
    return pattern.equals(toolName);
  }

  private static String describe(String hit, ToolPolicyRule rule) {
    return hit + "（#" + rule.getId() + " " + rule.getPattern() + "）";
  }
}
