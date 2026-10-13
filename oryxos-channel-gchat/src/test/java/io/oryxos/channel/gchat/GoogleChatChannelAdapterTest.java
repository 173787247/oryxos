package io.oryxos.channel.gchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 入站调用方校验的**接线**测试。
 *
 * <p>#918 指出 Google Chat 是唯一没有任何调用方校验的渠道。现在有两条闸：官方 JWT（配了 {@code extra.project_number} 才启用，见 {@link
 * GoogleChatCallerJwt}）与应用层的共享 token（{@code extra.inbound_token}）。这里不测校验逻辑本身 —— 那些在各自的测试类里 ——
 * 只测**它们真的接上了**， 因为「判断写了但某处没接」是这个代码库出过的缺陷类型。
 *
 * <p>用例都走 {@code start()} 之后的真实路径：校验器只在启动时装配，跳过启动就测不到接线。未携带 {@code Authorization}
 * 的请求在闸打开时被立刻拒绝，因此这些用例不打网络。
 */
class GoogleChatChannelAdapterTest {

  private static final String MESSAGE_BODY =
      "{\"type\":\"MESSAGE\",\"message\":{\"name\":\"spaces/AAA/messages/1\"}}";

  private static ProfileRegistry profileRegistry() {
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    return registry;
  }

  private static GoogleChatChannelAdapter started(Map<String, String> extra) {
    GoogleChatChannelAdapter adapter =
        new GoogleChatChannelAdapter(
            new ChannelConfig(
                "ops-gchat", "gchat", "app-id", "app-secret", "demo-agent", true, extra),
            profileRegistry(),
            null,
            url -> {});
    adapter.start();
    return adapter;
  }

  private static WebhookRequest inbound(String authorization) {
    Map<String, String> headers =
        authorization == null ? Map.of() : Map.of("authorization", authorization);
    return new WebhookRequest("POST", Map.of(), headers, MESSAGE_BODY);
  }

  @Test
  @DisplayName("未配任何闸：无凭据入站照常处理（行为不变）")
  void unconfiguredAcceptsCallerWithoutCredentials() {
    WebhookResponse resp = started(Map.of()).onWebhook(inbound(null));

    assertEquals(200, resp.status());
  }

  @Test
  @DisplayName("配了 project_number：官方 JWT 闸生效，无凭据入站 401 —— #918 里那条 curl 的形态")
  void jwtGateRejectsCallerWithoutToken() {
    WebhookResponse resp =
        started(Map.of(GoogleChatCallerJwt.EXTRA_PROJECT_NUMBER, "123456789"))
            .onWebhook(inbound(null));

    assertEquals(401, resp.status());
  }

  @Test
  @DisplayName("配了 inbound_token：共享 token 闸生效，无凭据入站 401")
  void tokenGateRejectsCallerWithoutToken() {
    WebhookResponse resp =
        started(Map.of("inbound_token", "s3cret-token")).onWebhook(inbound(null));

    assertEquals(401, resp.status());
  }

  @Test
  @DisplayName("两条闸都配：同样拒绝")
  void eitherGateRejectsCallerWithoutToken() {
    WebhookResponse resp =
        started(
                Map.of(
                    GoogleChatCallerJwt.EXTRA_PROJECT_NUMBER,
                    "123456789",
                    "inbound_token",
                    "s3cret-token"))
            .onWebhook(inbound(null));

    assertEquals(401, resp.status());
  }
}
