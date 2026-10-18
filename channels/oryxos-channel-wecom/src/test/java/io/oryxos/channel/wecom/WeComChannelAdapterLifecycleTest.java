package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class WeComChannelAdapterLifecycleTest {

  @TempDir Path workspaceRoot;

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

  @Test
  @DisplayName("重连路径复用 MessageSender，chatTypes 映射不丢失")
  void outboundStackReusedAcrossReconnectInit() throws Exception {
    ChannelConfig config =
        new ChannelConfig("ops-wecom", "wecom", "bot", "secret", "demo-agent", true);
    WeComChannelAdapter adapter =
        new WeComChannelAdapter(
            config,
            new ProfileRegistry(),
            mock(InboundMessageService.class),
            (OutboundGuard) url -> {},
            workspaceRoot);

    Method ensure = WeComChannelAdapter.class.getDeclaredMethod("ensureOutboundStack");
    ensure.setAccessible(true);
    Field senderField = WeComChannelAdapter.class.getDeclaredField("sender");
    senderField.setAccessible(true);

    ensure.invoke(adapter);
    WeComMessageSender first = (WeComMessageSender) senderField.get(adapter);
    first.rememberChatType("chat-1", 1);

    ensure.invoke(adapter);
    WeComMessageSender second = (WeComMessageSender) senderField.get(adapter);
    assertSame(first, second);
  }

  @Test
  @Timeout(20)
  @DisplayName("对端不回应 FIN_adapter.stop() 仍在约 2 秒内返回，不占住 ChannelAdminService 监视器")
  void stopBoundedWhenWsCloseNeverCompletes() throws Exception {
    ChannelConfig config =
        new ChannelConfig("ops-wecom", "wecom", "bot", "secret", "demo-agent", true);
    WeComChannelAdapter adapter =
        new WeComChannelAdapter(
            config,
            new ProfileRegistry(),
            mock(InboundMessageService.class),
            (OutboundGuard) url -> {},
            workspaceRoot);

    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch neverFinishes = new CountDownLatch(1);
    WeComWsClient neverClosing =
        new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {}) {
          @Override
          void closeWebSocket(WebSocket ws) {
            closeEntered.countDown();
            try {
              neverFinishes.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    seedSocket(neverClosing, mock(WebSocket.class));
    seedClient(adapter, neverClosing);

    long startedAt = System.nanoTime();
    try {
      adapter.stop();
    } finally {
      neverFinishes.countDown();
    }
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(closeEntered.await(1, TimeUnit.SECONDS), "close 必须真的被尝试过");
    assertTrue(elapsedMs >= 1_500, "stop() 应等满 2s 限时才返回，实际 " + elapsedMs + "ms");
    assertTrue(elapsedMs <= 4_000, "stop() 不得无限等待，实际 " + elapsedMs + "ms");
  }

  private static void seedSocket(WeComWsClient client, WebSocket ws) throws Exception {
    Field socketField = WeComWsClient.class.getDeclaredField("socket");
    socketField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<WebSocket> ref = (AtomicReference<WebSocket>) socketField.get(client);
    ref.set(ws);
  }

  private static void seedClient(WeComChannelAdapter adapter, WeComWsClient client)
      throws Exception {
    Field wsRefField = WeComChannelAdapter.class.getDeclaredField("wsRef");
    wsRefField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<WeComWsClient> ref = (AtomicReference<WeComWsClient>) wsRefField.get(adapter);
    ref.set(client);
  }

  @Test
  @Timeout(20)
  @DisplayName("stop() 在锁外关闭长连接：close 阻塞期间另一次 stop() 不被监视器挡住")
  void stopReleasesTheMonitorBeforeClosing() throws Exception {
    ChannelConfig config =
        new ChannelConfig("ops-wecom", "wecom", "bot", "secret", "demo-agent", true);
    WeComChannelAdapter adapter =
        new WeComChannelAdapter(
            config,
            new ProfileRegistry(),
            mock(InboundMessageService.class),
            (OutboundGuard) url -> {},
            workspaceRoot);

    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch neverFinishes = new CountDownLatch(1);
    WeComWsClient neverClosing =
        new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {}) {
          @Override
          void closeWebSocket(WebSocket ws) {
            closeEntered.countDown();
            try {
              neverFinishes.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    seedSocket(neverClosing, mock(WebSocket.class));
    seedClient(adapter, neverClosing);

    Thread first = Thread.ofPlatform().start(adapter::stop);
    try {
      assertTrue(closeEntered.await(2, TimeUnit.SECONDS), "第一次 stop 必须真的进入 close");

      // 关键断言：第一次 stop 还卡在 close 里时，第二次 stop 必须能立刻拿到监视器
      long startedAt = System.nanoTime();
      adapter.stop();
      long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
      assertTrue(elapsedMs < 800, "close 在锁外时第二次 stop 应立即返回，实际 " + elapsedMs + "ms（说明监视器被占住了）");
    } finally {
      neverFinishes.countDown();
      first.join(5_000);
    }
  }
}
