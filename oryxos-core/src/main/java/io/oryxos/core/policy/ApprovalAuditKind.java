package io.oryxos.core.policy;

/** 审批审计事件种类（042 / #464）：策略命中与最终决策分条落库，满足「命中 + 决策完整审计」。 */
public enum ApprovalAuditKind {

  /** 执行面命中 REQUIRE_APPROVAL（本刀随即 stub 拒绝并写 tool_invocations.blocked_by=approval）。 */
  HIT_REQUIRE,

  /** 执行面 / prompt 面命中 DENY。 */
  HIT_DENY,

  /** 人工批准（#466 stub；本刀只记审计）。 */
  APPROVED,

  /** 人工拒绝。 */
  DENIED,

  /** 超时拒绝（契约字段；耐久等待落地后由 #465/#466 写入）。 */
  TIMEOUT_DENIED
}
