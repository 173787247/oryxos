package io.oryxos.tool.notify;

import java.util.Map;

/** Google Chat Incoming Webhook 出站（type: gchat）。 */
public class GoogleChatNotifyAdapter implements NotifyChannelAdapter {

  private final NotifyPoster poster;

  public GoogleChatNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("gchat 渠道缺少 url 配置（Incoming Webhook）");
    }
    poster.postJson(url, Map.of("text", content));
  }
}
