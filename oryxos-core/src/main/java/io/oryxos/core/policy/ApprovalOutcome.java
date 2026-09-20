package io.oryxos.core.policy;

/**
 * 审批策略裁决结果（042 / #464）。
 *
 * <ul>
 *   <li>{@link #ALLOW} — 不需审批，执行面放行；prompt 可见
 *   <li>{@link #REQUIRE_APPROVAL} — 命中高风险规则；prompt 仍可见（模型可发起），执行面闸住；{@code
 *       oryxos.approval.durable-suspend=true} 时耐久挂起（#465），否则 stub 拒绝（#464）
 *   <li>{@link #DENY} — 策略直接拒绝；prompt 不可见且执行拒绝（与 020 deny 同口径的可见性语义）
 * </ul>
 */
public enum ApprovalOutcome {
  ALLOW,
  REQUIRE_APPROVAL,
  DENY
}
