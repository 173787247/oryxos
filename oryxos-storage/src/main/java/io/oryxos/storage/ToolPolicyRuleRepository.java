package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** tool_policy_rules 表访问（020-tool-policy）。规则量级几十行，全量读 + 内存匹配（R6 零缓存）。 */
public interface ToolPolicyRuleRepository extends JpaRepository<ToolPolicyRule, Long> {

  boolean existsByRuleTypeAndAgentNameAndPattern(String ruleType, String agentName, String pattern);
}
