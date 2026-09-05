package io.oryxos.core.cluster;

/** 同会话跨副本排队等待超限（026，FR-001）：前序轮次执行超长时后到消息不无限挂起——IM 路径以 「上一条消息还在处理，请稍候再发」反馈，Web 路径映射 429。 */
public class TurnWaitTimeoutException extends RuntimeException {

  public TurnWaitTimeoutException(String sessionId) {
    super("会话仍在处理上一条消息，等待超限: " + sessionId);
  }
}
