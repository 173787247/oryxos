package io.oryxos.channel.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code serviceUrls} 是渠道层里按外部标识只增不减的表之一：未配置 {@code extra.inbound_token} 时，Teams 的 入站 webhook
 * 不校验调用方（官方 JWT 校验由 Azure Bot Service / 反代承担，见 #918），任何能打到该端点的人都可以 用不同的 {@code conversation.id}
 * 往里灌条目 —— 所以表本身必须有上界。
 */
class TeamsChannelAdapterTest {

  private static final String SERVICE_URL = "https://smba.trafficmanager.net/teams/";
  private static final String INBOUND_TOKEN = "s3cret-token";

  private static TeamsChannelAdapter adapter() {
    return new TeamsChannelAdapter(
        new ChannelConfig("ops-teams", "teams", "app-id", "app-secret", "demo-agent", true),
        null,
        null,
        null);
  }

  /** 配了 {@code extra.inbound_token} 的实例：入站要求 {@code Authorization: Bearer <token>}。 */
  private static TeamsChannelAdapter adapterWithToken() {
    return new TeamsChannelAdapter(
        new ChannelConfig(
            "ops-teams",
            "teams",
            "app-id",
            "app-secret",
            "demo-agent",
            true,
            Map.of("inbound_token", INBOUND_TOKEN)),
        null,
        null,
        null);
  }

  private static WebhookRequest activity(String conversationId) {
    String body =
        "{\"serviceUrl\":\""
            + SERVICE_URL
            + "\",\"conversation\":{\"id\":\""
            + conversationId
            + "\"}}";
    return new WebhookRequest("POST", Map.of(), Map.of(), body);
  }

  private static WebhookRequest activityWithAuthorization(String conversationId, String header) {
    String body =
        "{\"serviceUrl\":\""
            + SERVICE_URL
            + "\",\"conversation\":{\"id\":\""
            + conversationId
            + "\"}}";
    return new WebhookRequest("POST", Map.of(), Map.of("authorization", header), body);
  }

  @Test
  @DisplayName("serviceUrl 表有上界：灌入远超上限的会话后不再增长")
  void serviceUrlsAreBounded() {
    TeamsChannelAdapter adapter = adapter();
    int attempts = TeamsChannelAdapter.MAX_TRACKED_CONVERSATIONS + 500;
    for (int i = 0; i < attempts; i++) {
      adapter.onWebhook(activity("conv-" + i));
    }
    assertEquals(TeamsChannelAdapter.MAX_TRACKED_CONVERSATIONS, adapter.trackedServiceUrlCount());
  }

  @Test
  @DisplayName("被淘汰的会话重新入站后又被记住 —— 淘汰不影响回复路径")
  void evictedConversationIsRememberedOnTheNextInbound() {
    TeamsChannelAdapter adapter = adapter();
    adapter.onWebhook(activity("conv-first"));
    for (int i = 0; i < TeamsChannelAdapter.MAX_TRACKED_CONVERSATIONS; i++) {
      adapter.onWebhook(activity("conv-" + i));
    }
    assertTrue(
        adapter.trackedServiceUrlCount() <= TeamsChannelAdapter.MAX_TRACKED_CONVERSATIONS,
        "表必须停在上限内");
    // 回复前必然先有一条入站消息，而入站会重新写入 —— 所以被淘汰的会话不会"再也回不了话"
    adapter.onWebhook(activity("conv-first"));
    assertEquals(SERVICE_URL, adapter.serviceUrlFor("conv-first"));
  }

  @Test
  @DisplayName("配了 inbound_token 后，无凭据入站被拒且不留 serviceUrl")
  void rejectsCallerWithoutToken() {
    TeamsChannelAdapter adapter = adapterWithToken();

    assertEquals(401, adapter.onWebhook(activity("conv-attacker")).status());
    assertEquals(0, adapter.trackedServiceUrlCount(), "被拒的请求不该在表里留下条目");
  }

  @Test
  @DisplayName("配了 inbound_token 后，错误的 Bearer 被拒")
  void rejectsWrongBearerToken() {
    TeamsChannelAdapter adapter = adapterWithToken();

    assertEquals(
        401,
        adapter
            .onWebhook(activityWithAuthorization("conv-wrong", "Bearer not-the-token"))
            .status());
    assertEquals(0, adapter.trackedServiceUrlCount());
  }

  @Test
  @DisplayName("配了 inbound_token 后，正确的 Bearer 照常处理")
  void acceptsCallerWithBearerToken() {
    TeamsChannelAdapter adapter = adapterWithToken();

    WebhookResponse resp =
        adapter.onWebhook(activityWithAuthorization("conv-ok", "Bearer " + INBOUND_TOKEN));

    assertEquals(200, resp.status());
    assertEquals(SERVICE_URL, adapter.serviceUrlFor("conv-ok"), "过了校验就该照常记住 serviceUrl");
  }

  @Test
  @DisplayName("未配置 inbound_token 时行为不变：无凭据入站照常处理")
  void unconfiguredAcceptsCallerWithoutCredentials() {
    TeamsChannelAdapter adapter = adapter();

    assertEquals(200, adapter.onWebhook(activity("conv-legacy")).status());
    assertEquals(SERVICE_URL, adapter.serviceUrlFor("conv-legacy"));
  }
}
