package io.oryxos.core.policy;

import java.util.List;

/**
 * 单次审批策略裁决（042 / #464）：prompt 可见性与执行面共用同一结果，保证两面一致。
 *
 * @param outcome 裁决
 * @param policyVersion 策略版本（审计用）
 * @param ruleId 命中规则 id；未命中为 null
 * @param actionType 分类结果；未分类可为 null
 * @param approvers 规则/默认审批人列表（不可变视图）
 * @param ttlSeconds 审批有效期秒；未配置可为 null
 * @param reason 人话理由（进审计 / 回填模型）
 */
public record ApprovalPolicyDecision(
    ApprovalOutcome outcome,
    String policyVersion,
    String ruleId,
    HighRiskActionType actionType,
    List<String> approvers,
    Integer ttlSeconds,
    String reason) {

  public ApprovalPolicyDecision {
    approvers = approvers == null ? List.of() : List.copyOf(approvers);
  }

  /** 未启用 / 未命中：放行。 */
  public static ApprovalPolicyDecision allow(String policyVersion) {
    return new ApprovalPolicyDecision(
        ApprovalOutcome.ALLOW, policyVersion, null, null, List.of(), null, null);
  }

  public boolean allowed() {
    return outcome == ApprovalOutcome.ALLOW;
  }

  public boolean requiresApproval() {
    return outcome == ApprovalOutcome.REQUIRE_APPROVAL;
  }

  public boolean denied() {
    return outcome == ApprovalOutcome.DENY;
  }

  /** prompt 面：仅 {@link ApprovalOutcome#DENY} 隐藏工具。 */
  public boolean visibleInPrompt() {
    return outcome != ApprovalOutcome.DENY;
  }
}
