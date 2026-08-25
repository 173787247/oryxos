package io.oryxos.core.channel;

/** 入站会话类型：私聊建持久会话，群聊每次 @ 为独立无状态问答（017 Clarify-Q3）。 */
public enum ChatKind {
  /** 私聊：按「渠道 + 用户 + Agent」维持多轮连续会话。 */
  P2P,
  /** 群聊：单次独立问答，不建跨提问会话历史，仅落审计。 */
  GROUP
}
