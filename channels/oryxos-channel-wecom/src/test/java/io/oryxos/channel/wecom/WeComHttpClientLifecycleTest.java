package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 企微长连接的 HttpClient 释放：客户端自己持有（#999 的模板），所以释放点是 {@code closeQuietly()}；本类钉住适配器在**断线重连**、{@code
 * stop()} 与建连失败三条路径上 确实走到了它 —— 断线那条正是旧实现漏掉的：{@code handleDisconnected} 只把引用置空。
 */
class WeComHttpClientLifecycleTest {

  @TempDir Path workspaceRoot;

  private static ChannelConfig config() {
    return new ChannelConfig("wecom-test", "wecom", "bot", "secret", "demo-agent", true);
  }

  private WeComChannelAdapter adapter() {
    return new WeComChannelAdapter(
        config(),
        mock(ProfileRegistry.class),
        mock(InboundMessageService.class),
        (OutboundGuard) url -> {},
        workspaceRoot);
  }

  private static WeComWsClient clientWith(HttpClient http) {
    return new WeComWsClient(
        "bot", "secret", WeComWsClient.DEFAULT_WS_URL, frame -> {}, () -> {}, () -> http);
  }

  @Test
  @DisplayName("断线重连：旧客户端必须被 close，它是 HttpClient 的唯一持有者")
  void disconnectReleasesTheStaleClientsHttpClient() throws Exception {
    HttpClient http = mock(HttpClient.class);
    WeComWsClient stale = clientWith(http);
    stale.httpClient(); // 懒创建：客户端持有它，但一次真实连接都不建
    WeComChannelAdapter adapter = adapter();
    prime(adapter, stale);

    adapter.handleDisconnected();

    verify(http).shutdownNow(); // 旧实现仅 wsRef.set(null)，selector 线程随重连累积泄漏
    adapter.stop(); // 取消 handleDisconnected 排下的重连任务
  }

  @Test
  @DisplayName("stop()：关闭当前客户端并释放其 HttpClient")
  void stopReleasesTheHttpClient() throws Exception {
    HttpClient http = mock(HttpClient.class);
    WeComWsClient current = clientWith(http);
    current.httpClient();
    WeComChannelAdapter adapter = adapter();
    prime(adapter, current);

    adapter.stop();

    verify(http).shutdownNow();
    adapter.stop(); // 幂等：引用已空，再 stop 不得抛
  }

  @Test
  @DisplayName("建连失败：客户端自己关闭并释放（适配器会丢弃它且不调 stop）")
  void failedConnectReleasesTheHttpClient() {
    HttpClient http = mock(HttpClient.class);
    WebSocket.Builder builder = mock(WebSocket.Builder.class);
    when(http.newWebSocketBuilder()).thenReturn(builder);
    when(builder.connectTimeout(any())).thenReturn(builder);
    CompletableFuture<WebSocket> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IOException("no network in test"));
    when(builder.buildAsync(any(), any())).thenReturn(failed);
    WeComWsClient client = clientWith(http);

    assertThrows(Exception.class, () -> client.connectAndSubscribe(Duration.ofMillis(200)));

    verify(http).shutdownNow();
  }

  /** 把客户端放进 wsRef 并置 running：这两步在生产里分别由 connect() / start() 完成，单测不建真连接。 */
  private static void prime(WeComChannelAdapter adapter, WeComWsClient client) throws Exception {
    Field ref = WeComChannelAdapter.class.getDeclaredField("wsRef");
    ref.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<WeComWsClient> wsRef = (AtomicReference<WeComWsClient>) ref.get(adapter);
    wsRef.set(client);
    Field running = WeComChannelAdapter.class.getDeclaredField("running");
    running.setAccessible(true);
    running.setBoolean(adapter, true);
  }
}
