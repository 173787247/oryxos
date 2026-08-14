package io.oryxos.tool.notify;

import java.util.Map;

/**
 * 飞书 / Lark 自定义机器人（type: feishu）。
 *
 * <p>二者协议相同、仅域名不同（open.feishu.cn / open.larksuite.com），URL 来自配置故一个实现覆盖两者。 body 格式：{@code
 * {"msg_type":"text","content":{"text":"..."}}}；签名校验为可选项，未开启时拿 URL 即可推。
 *
 * <p>出网经 {@link NotifyPoster}：禁自动重定向并每跳复检域名白名单。
 */
public class FeishuNotifyAdapter implements NotifyChannelAdapter {

  private final NotifyPoster poster;

  public FeishuNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("feishu 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    poster.postJson(url, Map.of("msg_type", "text", "content", Map.of("text", content)));
  }
}
