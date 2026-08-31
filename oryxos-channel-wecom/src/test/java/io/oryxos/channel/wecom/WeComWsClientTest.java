package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeComWsClientTest {

  @Test
  @DisplayName("onError 后清除订阅态，status 不应再报 CONNECTED")
  void onErrorClearsSubscriptionState() throws Exception {
    AtomicBoolean disconnected = new AtomicBoolean(false);
    WeComWsClient client =
        new WeComWsClient(
            "bot",
            "secret",
            WeComWsClient.DEFAULT_WS_URL,
            node -> {},
            () -> disconnected.set(true));
    var subscribed = WeComWsClient.class.getDeclaredField("subscribed");
    subscribed.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) subscribed.get(client)).set(true);
    var closed = WeComWsClient.class.getDeclaredField("closed");
    closed.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) closed.get(client)).set(false);

    client.onError(null, new RuntimeException("connection reset"));

    assertFalse(client.isSubscribed());
    assertTrue(disconnected.get());
  }
}
