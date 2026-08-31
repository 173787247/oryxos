package io.oryxos.core.policy;

import java.util.List;

/**
 * 工具策略契约（020-tool-policy）：平台管理员的治理层——「Agent 作者声明想用什么」（AGENT.md tools:）与
 * 「平台允许它用什么」（本策略）分离。收敛固定三步：AGENT_DENY &gt; (GLOBAL_DENY − AGENT_EXEMPT) &gt; 允许； 策略只做减法，有效工具集永远 ⊆
 * 声明集。
 *
 * <p>契约放 core 是依赖倒置：消费方 PromptBuilder（事前过滤）与 ToolExecutor（事中裁决）只认本接口， SQLite
 * 实现（ToolPolicyServiceImpl）在 oryxos-storage。与沙箱白名单正交：策略管「能不能用这个工具」， 沙箱管「工具能碰什么资源」，两道独立叠加、互不豁免（spec
 * FR-012）。
 */
public interface ToolPolicyService {

  /** 全允空实现：未配置策略/旧构造路径统一走它，行为与无策略时代完全一致（零破坏锚点）。 */
  ToolPolicyService ALLOW_ALL = (agentName, toolName) -> PolicyDecision.ALLOWED;

  /** 单工具裁决：允许，或拒绝并附命中规则的人话描述（审计原因与管理台「为什么」共用同一文案）。 */
  PolicyDecision check(String agentName, String toolName);

  /** 批量过滤（PromptBuilder 事前用）：返回声明集中被策略允许的子集，保持原有顺序。 */
  default List<String> filterAllowed(String agentName, List<String> toolNames) {
    return toolNames.stream().filter(name -> check(agentName, name).allowed()).toList();
  }

  /** 裁决结果：allowed=false 时 reason 为命中规则的人话描述（如「命中全局禁用规则（shell）」）。 */
  record PolicyDecision(boolean allowed, String reason) {

    public static final PolicyDecision ALLOWED = new PolicyDecision(true, null);

    public static PolicyDecision denied(String reason) {
      return new PolicyDecision(false, reason);
    }
  }
}
