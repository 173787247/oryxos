package io.oryxos.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Telegram 适配器的 HttpClient 生命周期：旧实现每次 {@code start()} 新建、{@code stop()} 从不 shutdown， 渠道反复启停会把
 * selector 线程一路攒到 GC。现在懒创建、跨 start 复用，stop 与启动失败各释放一次。
 */
class TelegramHttpClientLifecycleTest {

  @TempDir Path workspaceRoot;

  private static ChannelConfig config() {
    return new ChannelConfig("tg-test", "telegram", "bot-token", "bot-name", "demo-agent", true);
  }

  private TelegramChannelAdapter adapter(Supplier<HttpClient> factory) {
    ProfileRegistry profiles = mock(ProfileRegistry.class);
    when(profiles.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    return new TelegramChannelAdapter(
        config(),
        profiles,
        mock(InboundMessageService.class),
        (OutboundGuard) url -> {},
        workspaceRoot,
        "http://127.0.0.1:1",
        factory);
  }

  @Test
  @DisplayName("一个适配器只建一个 HttpClient：跨 start 复用；stop() 释放且可重复调用")
  void reusesOneHttpClientAndReleasesOnStop() {
    HttpClient client = mock(HttpClient.class);
    HttpClient afterStop = mock(HttpClient.class);
    AtomicInteger created = new AtomicInteger();
    TelegramChannelAdapter adapter =
        adapter(
            () -> {
              created.incrementAndGet();
              return created.get() == 1 ? client : afterStop;
            });

    assertSame(client, adapter.httpClient(), "懒创建后应缓存复用");
    assertSame(client, adapter.httpClient(), "再次 start 必须是同一实例");
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
}
