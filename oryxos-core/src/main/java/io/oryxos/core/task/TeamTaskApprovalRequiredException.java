package io.oryxos.core.task;

import io.oryxos.core.policy.ApprovalPolicyDecision;

/** Direction I HITL：团队任务被审批策略拦住（REQUIRE_APPROVAL / DENY）。Web 映射 403；耐久挂起续刀另开。 */
public final class TeamTaskApprovalRequiredException extends RuntimeException {

  public TeamTaskApprovalRequiredException(ApprovalPolicyDecision decision) {
    super(messageFor(decision));
  }

  private static String messageFor(ApprovalPolicyDecision d) {
    String reason = d == null || d.reason() == null || d.reason().isBlank() ? "需要人工审批" : d.reason();
    if (d != null && d.requiresApproval()) {
      return "需要人工审批：" + reason;
    }
    return "被审批策略拒绝：" + reason;
  }
}
