package io.oryxos.tool.notify;

import java.util.Map;

/**
 * 企业微信群机器人（type: wecom）。
 *
 * <p>webhook 形态与通用档相同，仅 body 格式不同：{@code {"msgtype":"text","text":{"content":"..."}}}。
 * 注意这是"群机器人"档——"应用消息"（corpid/corpsecret 换 AccessToken）属扩展阶段，不在此。
 *
 * <p>出网经 {@link NotifyPoster}：禁自动重定向并每跳复检域名白名单。
 */
public class WeComNotifyAdapter implements NotifyChannelAdapter {

  private final NotifyPoster poster;

  public WeComNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("wecom 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    poster.postJson(url, Map.of("msgtype", "text", "text", Map.of("content", content)));
  }
}
