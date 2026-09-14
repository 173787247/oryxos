package io.oryxos.web.controller.dto;

import java.util.Map;

/**
 * 新建通知渠道请求体：name 全局唯一；url 为 webhook 类渠道地址，config 承载类型相关多字段（email SMTP，或 telegram token/chat_id 等）。
 */
public record CreateNotifyChannelRequest(
    String name, String type, String url, String description, Map<String, String> config) {

  /** 防御性拷贝：config 是可变 Map，入站前固化不可变（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）。 */
  public CreateNotifyChannelRequest {
    config = config == null ? Map.of() : Map.copyOf(config);
  }
}
