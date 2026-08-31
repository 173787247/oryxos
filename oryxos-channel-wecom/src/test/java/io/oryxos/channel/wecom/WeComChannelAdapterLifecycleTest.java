package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeComChannelAdapterLifecycleTest {

  @Test
  @DisplayName("重连退避：指数增长并封顶")
  void reconnectDelayUsesExponentialBackoffWithCap() {
    assertEquals(2_000L, WeComChannelAdapter.reconnectDelayMs(0));
    assertEquals(4_000L, WeComChannelAdapter.reconnectDelayMs(1));
    assertEquals(8_000L, WeComChannelAdapter.reconnectDelayMs(2));
    assertEquals(60_000L, WeComChannelAdapter.reconnectDelayMs(10));
  }

  @Test
  @DisplayName("重连退避：负 attempt 按 0 处理")
  void reconnectDelayClampsNegativeAttempt() {
    assertTrue(WeComChannelAdapter.reconnectDelayMs(-1) >= 2_000L);
  }
}
