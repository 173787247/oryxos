package io.oryxos.channel.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.channel.StubHttpClient;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 钉钉 Stream 长连接的 HttpClient 生命周期（见 Slack 同名用例的说明）。 */
class DingTalkHttpClientLifecycleTest {

  @TempDir Path workspaceRoot;

  private static ChannelConfig config() {
    return new ChannelConfig(
        "dingtalk-test", "dingtalk", "bot-id", "bot-secret", "demo-agent", true);
  }

  private DingTalkChannelAdapter adapter(Supplier<HttpClient> factory) {
    ProfileRegistry profiles = mock(ProfileRegistry.class);
    when(profiles.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    return new DingTalkChannelAdapter(
        config(),
        profiles,
        mock(InboundMessageService.class),
        (OutboundGuard) url -> {},
        workspaceRoot,
        factory);
  }

  @Test
  @DisplayName("一个适配器只建一个 HttpClient：跨重连复用；stop() 释放且可重复调用")
  void reusesOneHttpClientAndReleasesOnStop() {
    HttpClient client = mock(HttpClient.class);
    HttpClient afterStop = mock(HttpClient.class);
    AtomicInteger created = new AtomicInteger();
    DingTalkChannelAdapter adapter =
        adapter(
            () -> {
              created.incrementAndGet();
              return created.get() == 1 ? client : afterStop;
            });

    assertSame(client, adapter.httpClient(), "懒创建后应缓存复用");
    assertSame(client, adapter.httpClient(), "重连再次取用必须是同一实例");
    assertEquals(1, created.get(), "一个适配器只应创建一个 HttpClient");

    adapter.stop();
    verify(client).shutdownNow();

    adapter.stop(); // 幂等：没有实例时再 stop 不得抛

    // stop() 之后旧实例已 shutdown、不可复用：下一次取用（下一次 start）必须懒创建新实例
    assertSame(afterStop, adapter.httpClient(), "stop() 之后必须新建，不能复用已 shutdown 的实例");
    assertEquals(2, created.get(), "旧的已释放，新的按需重建");
    adapter.stop();
    verify(afterStop).shutdownNow();
  }

  @Test
  @DisplayName("start() 失败：先释放再抛；再次 start 懒创建新实例（旧实例已 shutdown）")
  void failedStartReleasesAndTheNextStartCreatesAFreshClient() throws Exception {
    HttpClient first = mock(HttpClient.class);
    HttpClient second = mock(HttpClient.class);
    doReturn(StubHttpClient.response(500, "{}", null)).when(first).send(any(), any());
    doReturn(StubHttpClient.response(500, "{}", null)).when(second).send(any(), any());
    AtomicInteger created = new AtomicInteger();
    DingTalkChannelAdapter adapter = adapter(() -> created.incrementAndGet() == 1 ? first : second);

    assertThrows(IllegalStateException.class, adapter::start);
    verify(first).shutdownNow(); // admin 丢弃适配器时不会调 stop()，必须在这里释放

    assertThrows(IllegalStateException.class, adapter::start);
    assertEquals(2, created.get(), "旧实例已 shutdown，新的 start 必须懒创建新实例");

    adapter.stop();
    verify(second).shutdownNow();
  }

  @Test
  @DisplayName("Stream 客户端只用注入的 HttpClient：不自建、不 shutdown（所有权在适配器）")
  void streamClientUsesTheInjectedClientOnly() throws Exception {
    HttpClient client = mock(HttpClient.class);
    doReturn(StubHttpClient.response(500, "{}", null)).when(client).send(any(), any());
    DingTalkStreamClient streamClient =
        new DingTalkStreamClient("bot-id", "bot-secret", url -> {}, node -> {}, kind -> {});

    assertThrows(Exception.class, () -> streamClient.connect(client, Duration.ofMillis(200)));

    verify(client).send(any(), any());
    verify(client, never()).newWebSocketBuilder();
    verify(client, never()).shutdownNow();
  }
}
