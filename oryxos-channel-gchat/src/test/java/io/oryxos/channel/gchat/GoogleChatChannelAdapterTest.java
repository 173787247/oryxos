package io.oryxos.channel.gchat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 入站调用方校验的接线测试。
 *
 * <p>#918 指出 Google Chat 是唯一没有任何调用方校验的渠道。官方的校验方式是 Google 签发的 JWT（由边缘或反代承担， 不在 core 引入 OpenID
 * 依赖），这里测的是应用层的第二道闸：配了 {@code extra.inbound_token} 就必须带上 {@code Authorization: Bearer <token>}，否则
 * 401 且不进编排。
 *
 * <p>依赖传 {@code null}：{@code normalizer} 为空时 {@code onWebhook} 不会触碰编排，因此校验行为可以单独钉住。
 */
class GoogleChatChannelAdapterTest {

  private static final String INBOUND_TOKEN = "s3cret-token";
  private static final String MESSAGE_BODY =
      "{\"type\":\"MESSAGE\",\"message\":{\"name\":\"spaces/AAA/messages/1\"}}";

  private static GoogleChatChannelAdapter adapter() {
    return new GoogleChatChannelAdapter(
        new ChannelConfig("ops-gchat", "gchat", "app-id", "app-secret", "demo-agent", true),
        null,
        null,
        null);
  }

  private static GoogleChatChannelAdapter adapterWithToken() {
    return new GoogleChatChannelAdapter(
        new ChannelConfig(
            "ops-gchat",
            "gchat",
            "app-id",
            "app-secret",
            "demo-agent",
            true,
            Map.of("inbound_token", INBOUND_TOKEN)),
        null,
        null,
        null);
  }

  private static WebhookRequest inbound(String authorization) {
    Map<String, String> headers =
        authorization == null ? Map.of() : Map.of("authorization", authorization);
    return new WebhookRequest("POST", Map.of(), headers, MESSAGE_BODY);
  }

  @Test
  @DisplayName("配了 inbound_token 后，无凭据入站被拒 —— 这是 #918 里那条 curl 的形态")
  void rejectsCallerWithoutToken() {
    WebhookResponse resp = adapterWithToken().onWebhook(inbound(null));

    assertEquals(401, resp.status());
  }

  @Test
  @DisplayName("配了 inbound_token 后，错误的 Bearer 被拒")
  void rejectsWrongBearerToken() {
    assertEquals(401, adapterWithToken().onWebhook(inbound("Bearer not-the-token")).status());
  }

  @Test
  @DisplayName("配了 inbound_token 后，正确的 Bearer 放行到解析层")
  void acceptsCallerWithBearerToken() {
    assertEquals(200, adapterWithToken().onWebhook(inbound("Bearer " + INBOUND_TOKEN)).status());
  }

  @Test
  @DisplayName("未配置 inbound_token 时行为不变：无凭据入站照常处理")
  void unconfiguredAcceptsCallerWithoutCredentials() {
    assertEquals(200, adapter().onWebhook(inbound(null)).status());
  }
}
