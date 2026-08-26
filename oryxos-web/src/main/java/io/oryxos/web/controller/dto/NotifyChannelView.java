package io.oryxos.web.controller.dto;

import io.oryxos.core.notify.NotifyChannelDef;
import java.util.Map;

/** 通知渠道视图（列表/详情返回）。 */
public record NotifyChannelView(
    String name, String type, String url, String description, Map<String, String> config) {

  /** 防御性拷贝：config 是可变 Map，出站前固化不可变（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）。 */
  public NotifyChannelView {
    config = config == null ? Map.of() : Map.copyOf(config);
  }

  public static NotifyChannelView from(NotifyChannelDef d) {
    return new NotifyChannelView(d.name(), d.type(), d.url(), d.description(), d.config());
  }
}
