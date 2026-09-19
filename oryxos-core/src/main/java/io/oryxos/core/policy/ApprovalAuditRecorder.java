package io.oryxos.core.policy;

/**
 * 审批审计写入契约（042 / #464）。实现 fail-open：审计失败不得阻断主链路（与 ToolInvocationAuditor
 * 同纪律的调用方责任——本接口实现应尽量自吞或上抛由调用方吞）。
 */
public interface ApprovalAuditRecorder {

  ApprovalAuditRecorder NOOP = (event) -> {};

  void record(ApprovalAuditEvent event);

  /**
   * 一条审批审计。
   *
   * @param kind 命中或最终决策
   * @param sessionId 会话（可空——prompt 面 DENY 无会话时）
   * @param agentName Agent
   * @param toolName 工具
   * @param actionType 动作类型名
   * @param policyVersion 策略版本
   * @param ruleId 规则 id
   * @param actor 审批人 / 系统
   * @param reason 人话
   * @param ttlSeconds 有效期
   */
  record ApprovalAuditEvent(
      ApprovalAuditKind kind,
      String sessionId,
      String agentName,
      String toolName,
      String actionType,
      String policyVersion,
      String ruleId,
      String actor,
      String reason,
      Integer ttlSeconds) {}
}
