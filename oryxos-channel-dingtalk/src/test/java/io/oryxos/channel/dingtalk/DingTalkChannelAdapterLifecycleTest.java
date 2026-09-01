package io.oryxos.channel.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DingTalkChannelAdapterLifecycleTest {

  @Test
  @DisplayName("重连退避：指数增长并封顶")
  void reconnectDelayUsesExponentialBackoffWithCap() {
    assertEquals(2_000L, DingTalkChannelAdapter.reconnectDelayMs(0));
    assertEquals(4_000L, DingTalkChannelAdapter.reconnectDelayMs(1));
    assertEquals(8_000L, DingTalkChannelAdapter.reconnectDelayMs(2));
    assertEquals(60_000L, DingTalkChannelAdapter.reconnectDelayMs(10));
  }

  @Test
  @DisplayName("重连退避：负 attempt 按 0 处理")
  void reconnectDelayClampsNegativeAttempt() {
    assertTrue(DingTalkChannelAdapter.reconnectDelayMs(-1) >= 2_000L);
  }
}
