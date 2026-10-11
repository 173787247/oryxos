package io.oryxos.channel.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.WebhookRequest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code serviceUrls} 是渠道层里按外部标识只增不减的表之一：Teams 的入站 webhook 不做调用方验证（见 #918），任何能打到该端点的人都可以用不同的 {@code
 * conversation.id} 往里灌条目。
 */
class TeamsChannelAdapterTest {

  private static final String SERVICE_URL = "https://smba.trafficmanager.net/teams/";

  private static TeamsChannelAdapter adapter() {
    return new TeamsChannelAdapter(
        new ChannelConfig("ops-teams", "teams", "app-id", "app-secret", "demo-agent", true),
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
}
