package io.oryxos.web.controller.dto;

/** 发消息 / 无状态调用的回复；traceId（021）供报障定位与审计回放，纯增量字段。 */
public record MessageResponse(String reply, String traceId) {

  /** 旧单参形态保留（既有构造点/测试兼容）：无 trace 场景委托 null。 */
  public MessageResponse(String reply) {
    this(reply, null);
  }
}
