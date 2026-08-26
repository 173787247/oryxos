package io.oryxos.web.controller.dto;

import java.util.Map;

/** 更新通知渠道请求体：name 在路径上，这里改 type / url / config / 描述。 */
public record UpdateNotifyChannelRequest(
    String type, String url, String description, Map<String, String> config) {

  /** 防御性拷贝：config 是可变 Map，入站前固化不可变（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）。 */
  public UpdateNotifyChannelRequest {
    config = config == null ? Map.of() : Map.copyOf(config);
  }
}
