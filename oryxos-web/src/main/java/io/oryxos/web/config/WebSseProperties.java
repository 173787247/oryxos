package io.oryxos.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 流式响应配置（019-sse-streaming）。
 *
 * <p>{@code oryxos.web.sse.heartbeat-seconds}：事件流静默期的心跳间隔（SSE 注释行 {@code : ping}，防中间代理
 * 空闲断连，FR-007）。固定间隔、不做空闲计时重置——多发心跳无害（analyze A1 裁决）。
 */
@ConfigurationProperties(prefix = "oryxos.web.sse")
public class WebSseProperties {

  /** 心跳间隔（秒）。默认 15——低于常见代理/网关 30~60 秒的空闲超时。 */
  private long heartbeatSeconds = 15;

  public long getHeartbeatSeconds() {
    return heartbeatSeconds;
  }

  public void setHeartbeatSeconds(long heartbeatSeconds) {
    this.heartbeatSeconds = heartbeatSeconds;
  }
}
