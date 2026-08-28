package io.oryxos.web.controller.dto;

import java.util.Map;

/**
 * 新建通知渠道请求体：name 全局唯一，type∈{webhook,feishu,wecom,dingtalk,email}；url 为 HTTP 类渠道的 webhook 地址，config
 * 承载类型相关多字段（如 email 的 host/port/from/to）。
 */
public record CreateNotifyChannelRequest(
    String name, String type, String url, String description, Map<String, String> config) {

  /** 防御性拷贝：config 是可变 Map，入站前固化不可变（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）。 */
  public CreateNotifyChannelRequest {
    config = config == null ? Map.of() : Map.copyOf(config);
  }
}
