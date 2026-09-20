package io.oryxos.core.policy;

/** 审批拒绝 / 超时语义（042 / #464 契约字段）。本刀执行面 stub 仅落实 {@link #REJECT}；挂起恢复见 #465。 */
public enum ApprovalDenySemantics {

  /** 立即拒绝目标动作（零执行），回填可读失败原因。 */
  REJECT,

  /** 超时未批视为拒绝（#465/#466 耐久等待落地后生效；本刀配置可解析、执行仍走 REJECT stub）。 */
  TIMEOUT_REJECT
}
