package io.oryxos.core.policy;

/**
 * 高风险动作审批策略契约（042 / #464 / epic #455）。
 *
 * <p>与 {@link ToolPolicyService} 正交：020 管「能不能用这个工具」（减法），本接口管「用之前要不要人批」。两道独立叠加：先 Tool Policy，再
 * Approval。
 *
 * <p>默认 {@link #PASS_THROUGH}：未启用 / 旧构造路径零行为变化。
 */
public interface ApprovalPolicyService {

  /** 全放行：未启用审批策略时注入，行为与无 HITL 时代一致。 */
  ApprovalPolicyService PASS_THROUGH =
      new ApprovalPolicyService() {
        @Override
        public ApprovalPolicyDecision evaluate(
            String agentName, String toolName, String argumentsJson) {
          return ApprovalPolicyDecision.allow(null);
        }
      };

  /**
   * 对一次拟议工具调用裁决。prompt 与执行面必须调用同一实现、同一策略版本。
   *
   * @param agentName Agent 名
   * @param toolName 工具注册名（含 MCP）
   * @param argumentsJson 参数 JSON（可空；本刀分类主要靠工具名 / 动作类型，参数匹配留后续）
   */
  ApprovalPolicyDecision evaluate(String agentName, String toolName, String argumentsJson);

  /** prompt 可见性：与 {@link ApprovalPolicyDecision#visibleInPrompt()} 同源。 */
  default boolean isVisibleInPrompt(String agentName, String toolName) {
    return evaluate(agentName, toolName, null).visibleInPrompt();
  }

  /** 执行面策略命中审计（HIT_REQUIRE / HIT_DENY）。默认空；{@link ConfigApprovalPolicyServiceImpl} 落库。 */
  default void recordHit(
      String sessionId, String agentName, String toolName, ApprovalPolicyDecision decision) {}

  /**
   * 人工审批最终决策 stub（#466 入口预留）：只写审计，不恢复执行（#465）。默认空操作。
   *
   * @return true 表示已受理（审计已记）；PASS_THROUGH 恒 false
   */
  default boolean recordHumanDecision(ApprovalHumanDecision decision) {
    return false;
  }
}
