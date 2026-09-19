package io.oryxos.core.policy;

/**
 * 人工审批最终决策 stub（042 / #464）：供 #466 回调写入审计；本刀<strong>不</strong>恢复执行（#465）。
 *
 * @param sessionId 会话
 * @param agentName Agent
 * @param toolName 工具
 * @param policyVersion 策略版本
 * @param ruleId 命中规则
 * @param approved true=批准，false=拒绝
 * @param actor 审批人标识
 * @param comment 意见（可空）
 */
public record ApprovalHumanDecision(
    String sessionId,
    String agentName,
    String toolName,
    String policyVersion,
    String ruleId,
    boolean approved,
    String actor,
    String comment) {}
