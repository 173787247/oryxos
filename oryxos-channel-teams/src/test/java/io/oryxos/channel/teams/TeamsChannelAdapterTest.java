package io.oryxos.channel.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code serviceUrls} 是渠道层里按外部标识只增不减的表之一：闸没打开时，Teams 的入站 webhook 不校验调用方， 任何能打到该端点的人都可以用不同的 {@code
 * conversation.id} 往里灌条目 —— 所以表本身必须有上界。
 *
 * <p>另外钉住两条调用方闸的**接线**（官方的 Bot Framework JWT 与共享 token）：校验器只在 {@code start()} 时
 * 装配，因此用例都走启动后的真实路径。「判断写了但某处没接」是这个代码库出过的缺陷类型。
 */
class TeamsChannelAdapterTest {

  private static final String SERVICE_URL = "https://smba.trafficmanager.net/teams/";
  private static final String INBOUND_TOKEN = "s3cret-token";
  private static final String TENANT_ID = "tenant-1";

  private static ProfileRegistry profileRegistry() {
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    return registry;
  }

  private static TeamsChannelAdapter started(Map<String, String> extra) {
    Map<String, String> all = new HashMap<>(extra);
    all.putIfAbsent("tenant_id", TENANT_ID);
    TeamsChannelAdapter adapter =
        new TeamsChannelAdapter(
            new ChannelConfig(
                "ops-teams", "teams", "app-id", "app-secret", "demo-agent", true, all),
            profileRegistry(),
            null,
            url -> {});
    adapter.start();
    return adapter;
  }

  private static WebhookRequest activity(String conversationId) {
    return activity(conversationId, null);
  }

  private static WebhookRequest activity(String conversationId, String authorization) {
    String body =
        "{\"serviceUrl\":\""
            + SERVICE_URL
            + "\",\"conversation\":{\"id\":\""
            + conversationId
            + "\"}}";
    Map<String, String> headers =
        authorization == null ? Map.of() : Map.of("authorization", authorization);
    return new WebhookRequest("POST", Map.of(), headers, body);
  }

  @Test
  @DisplayName("serviceUrl 表有上界：灌入远超上限的会话后不再增长")
  void serviceUrlsAreBounded() {
    TeamsChannelAdapter adapter = started(Map.of());
    int attempts = TeamsChannelAdapter.MAX_TRACKED_CONVERSATIONS + 500;
    for (int i = 0; i < attempts; i++) {
      adapter.onWebhook(activity("conv-" + i));
    }
    assertEquals(TeamsChannelAdapter.MAX_TRACKED_CONVERSATIONS, adapter.trackedServiceUrlCount());
  }

  @Test
  @DisplayName("被淘汰的会话重新入站后又被记住 —— 淘汰不影响回复路径")
  void evictedConversationIsRememberedOnTheNextInbound() {
    TeamsChannelAdapter adapter = started(Map.of());
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

  // ---------- 调用方闸的接线 ----------

  @Test
  @DisplayName("闸未打开：无凭据入站照常处理且照常记 serviceUrl（行为不变）")
  void unconfiguredAcceptsCallerWithoutCredentials() {
    TeamsChannelAdapter adapter = started(Map.of());

    assertEquals(200, adapter.onWebhook(activity("conv-legacy")).status());
    assertEquals(SERVICE_URL, adapter.serviceUrlFor("conv-legacy"));
  }

  @Test
  @DisplayName("配了 verify_caller_jwt：官方 JWT 闸生效，无凭据入站 401 且不留 serviceUrl")
  void jwtGateRejectsCallerWithoutToken() {
    TeamsChannelAdapter adapter = started(Map.of(TeamsCallerJwt.EXTRA_VERIFY_CALLER_JWT, "true"));

    assertEquals(401, adapter.onWebhook(activity("conv-attacker")).status());
    assertEquals(0, adapter.trackedServiceUrlCount(), "被拒的请求不该在表里留下条目");
  }

  @Test
  @DisplayName("配了 inbound_token：共享 token 闸生效，无凭据入站 401")
  void tokenGateRejectsCallerWithoutToken() {
    TeamsChannelAdapter adapter = started(Map.of("inbound_token", INBOUND_TOKEN));

    assertEquals(401, adapter.onWebhook(activity("conv-attacker")).status());
    assertEquals(0, adapter.trackedServiceUrlCount());
  }

  @Test
  @DisplayName("verify_caller_jwt 为 false / 缺省：闸不开，无凭据入站照常处理")
  void jwtGateStaysClosedUnlessExplicitlyEnabled() {
    assertEquals(
        200,
        started(Map.of(TeamsCallerJwt.EXTRA_VERIFY_CALLER_JWT, "false"))
            .onWebhook(activity("conv-a"))
            .status());
    assertEquals(200, started(Map.of()).onWebhook(activity("conv-b")).status());
  }
}
