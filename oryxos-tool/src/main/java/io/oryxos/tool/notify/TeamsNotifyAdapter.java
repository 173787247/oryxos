package io.oryxos.tool.notify;

import java.util.Map;

/** Microsoft Teams Incoming Webhook 出站（type: teams）。MVP 纯文本，不做自适应卡片。 */
public class TeamsNotifyAdapter implements NotifyChannelAdapter {

  private final NotifyPoster poster;

  public TeamsNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("teams 渠道缺少 url 配置（Incoming Webhook）");
    }
    poster.postJson(url, Map.of("text", content));
  }
}
