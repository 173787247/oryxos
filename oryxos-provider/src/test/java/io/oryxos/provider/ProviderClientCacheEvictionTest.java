package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * provider 客户端缓存的回收（31 节动态 provider）：{@code ProviderRegistry} 删掉一个 provider 后，provider 侧按名缓存的
 * {@link ChatModel} 必须一起丢掉。
 *
 * <p>为什么这值得修：每个 ChatModel 内部是 {@code SpringAiOpenAiHttpClient} → {@code okhttp3.OkHttpClient}（自带
 * dispatcher 线程池与连接池）。只增不减的话，每删一个 provider 就永久留下一个不再被任何配置引用的 HTTP 客户端——与 #999/#1003 修的 HttpClient
 * 泄漏同类。删除是低频管理动作，所以这是慢增长而非热路径问题，但回收本身只是一次 map remove。
 *
 * <p>可观测口径：缓存条目是否还在，用「同一个 provider 再调一次会不会重建 ChatModel」来判定（builder 计数）。缓存是 provider 内部 结构，外部没有可读的
 * size/keys；这个口径正是既有 {@code provider配置变更_ChatModel缓存原地替换不累积} 用的那个。
 */
class ProviderClientCacheEvictionTest {

  private static final String PROVIDER = "deepseek";
  private static final String MODEL = "model-x";
  private static final String AGENT = "test-agent";

  private ChatModel model;
  private LlmCallAuditor audit;
  private AtomicInteger builds;
  private SpringAiProviderServiceImpl service;

  @BeforeEach
  void setUp() {
    model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(reply("ok"));
    audit = mock(LlmCallAuditor.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    when(registry.find(PROVIDER))
        .thenReturn(
            Optional.of(new ProviderDef(PROVIDER, "key", "https://api.deepseek.com/v1", null)));
    builds = new AtomicInteger();
    service =
        new SpringAiProviderServiceImpl(
            registry,
            def -> {
              builds.incrementAndGet();
              return model;
            },
            new ToolSchemaAdapter(),
            audit,
            (p, m) -> Optional.empty());
  }

  @Test
  @DisplayName("evict 之后旧客户端被丢弃：下一次调用重建，而不是继续复用被删 provider 的实例")
  void evictDropsTheCachedClientSoTheNextCallRebuilds() {
    service.chat("s-1", profile(), request());
    service.chat("s-1", profile(), request());
    assertThat(builds.get()).isEqualTo(1); // 同配置复用

    service.evict(PROVIDER);

    service.chat("s-1", profile(), request());
    // 缺陷形态：没有回收 ⇒ 删掉（evict）之后仍然复用旧实例，计数停在 1，客户端永不释放
    assertThat(builds.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("evict 未缓存的名字：不抛异常、不影响其它条目")
  void evictUnknownNameIsANoOp() {
    service.chat("s-1", profile(), request());

    service.evict("never-seen");
    service.evict(null);
    service.evict(" ");

    service.chat("s-1", profile(), request());
    assertThat(builds.get()).isEqualTo(1); // 仍复用，没被误清
  }

  @Test
  @DisplayName("evict 撞上进行中的调用：这次调用照常返回完整结果，审计一条成功行")
  void evictDuringAnInFlightCallDoesNotDisturbIt() throws Exception {
    CountDownLatch insideCall = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              insideCall.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return reply("完整回复");
            });

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<ProviderResponse> inFlight =
          pool.submit(() -> service.chat("s-1", profile(), request()));
      assertThat(insideCall.await(5, TimeUnit.SECONDS)).isTrue();

      // 删除撞上进行中的调用：只摘缓存引用，不 close，调用方手里的实例仍在用
      service.evict(PROVIDER);
      release.countDown();

      ProviderResponse response = inFlight.get(5, TimeUnit.SECONDS);
      assertThat(response.text()).isEqualTo("完整回复");
      verify(audit, times(1))
          .record(
              eq("s-1"),
              eq(AGENT),
              eq(PROVIDER),
              eq(MODEL),
              any(),
              any(),
              eq(true),
              isNull(),
              anyLong());
      // 回收只影响"下一次"取用：这条在途调用没有被中途打断，结束后再调才重建
      assertThat(builds.get()).isEqualTo(1);
      service.chat("s-1", profile(), request());
      assertThat(builds.get()).isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  private static Profile profile() {
    return new Profile(
        AGENT,
        null,
        null,
        new Profile.ProviderRef(PROVIDER, MODEL, null),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static ProviderRequest request() {
    return ProviderRequest.of("hi");
  }

  private static ChatResponse reply(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
