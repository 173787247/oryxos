package io.oryxos.tool.notify;

import java.util.Map;

/** Mattermost Incoming Webhook 出站（type: mattermost）。 */
public class MattermostNotifyAdapter implements NotifyChannelAdapter {

  private final NotifyPoster poster;

  public MattermostNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("mattermost 渠道缺少 url 配置（Incoming Webhook）");
    }
    poster.postJson(url, Map.of("text", content));
  }
}
