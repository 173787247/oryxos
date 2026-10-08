package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openai.core.Timeout;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 超时语义回归：{@code read-timeout-seconds} 只该管「多久没读到数据算挂」，{@code connect-timeout-seconds} 只该管「建连多久算挂」。
 *
 * <p>与 {@link ProviderChatModelFactoryTimeoutTest}（挂死端点必须有界失败）互为护栏：那条守「不能无限等」，这条守「不能被整请求墙钟提前掐断」。
 */
class ProviderChatModelFactoryTimeoutSemanticsTest {

  /** 慢速 SSE 端点：分段间隔远小于读取超时，但总时长明显超过它。 */
  private static final int CHUNKS = 8;

  private static final long GAP_MILLIS = 500;

  /** 黑洞地址（RFC 1918 私有段内的不可达主机）：SYN 有去无回，用于量 connect 超时。 */
  private static final String BLACKHOLE_HOST = "10.255.255.1";

  private static final int BLACKHOLE_PORT = 9;

  private HttpServer slowStreamServer;

  private ExecutorService serverExecutor;

  private final CountDownLatch hangRelease = new CountDownLatch(1);

  private volatile boolean hang;

  /** 每次测试可调的慢速端点形状（handler 在处理请求时才读，故 volatile）。 */
  private volatile int chunkCount = CHUNKS;

  private volatile long gapMillis = GAP_MILLIS;

  @BeforeEach
  void startSlowStreamServer() throws Exception {
    slowStreamServer = HttpServer.create(new InetSocketAddress(0), 0);
    slowStreamServer.createContext("/", this::streamSlowly);
    serverExecutor = Executors.newCachedThreadPool();
    slowStreamServer.setExecutor(serverExecutor);
    slowStreamServer.start();
  }

  @AfterEach
  void stopSlowStreamServer() {
    hangRelease.countDown();
    slowStreamServer.stop(0);
    serverExecutor.shutdownNow();
    System.clearProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP);
    System.clearProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP);
  }

  @Test
  @DisplayName("健康长流式回答_总时长超过读取超时_每个分片间隔远小于它_必须完整跑完")
  void longStreamIsNotCutByWholeRequestWallClock() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "2");
    ChatModel model =
        modelFor("slow", "http://127.0.0.1:" + slowStreamServer.getAddress().getPort());

    long startNanos = System.nanoTime();
    List<ChatResponse> chunks =
        model.stream(new Prompt("ping")).collectList().block(Duration.ofSeconds(30));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

    assertThat(deltas(chunks)).hasSize(CHUNKS);
    assertThat(deltas(chunks).get(CHUNKS - 1)).isEqualTo("chunk-" + (CHUNKS - 1));
    // 跑过了读取超时那份墙钟：掐断它的不该是「总时长」
    assertThat(elapsedMillis).isGreaterThan(2_000L);
  }

  @Test
  @DisplayName("请求级 options 的 Duration 不再被当成整请求墙钟_生产路径同款（SpringAiProviderServiceImpl 逐次传 options）")
  void perRequestOptionsDurationIsNotAWholeRequestWallClock() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "30");
    ChatModel model =
        modelFor("slow", "http://127.0.0.1:" + slowStreamServer.getAddress().getPort());
    Prompt perRequest =
        new Prompt(
            "ping",
            OpenAiChatOptions.builder().model("slow-model").timeout(Duration.ofSeconds(2)).build());

    long startNanos = System.nanoTime();
    List<ChatResponse> chunks =
        model.stream(perRequest).collectList().block(Duration.ofSeconds(30));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

    assertThat(deltas(chunks)).hasSize(CHUNKS);
    assertThat(elapsedMillis).isGreaterThan(2_000L);
  }

  @Test
  @DisplayName("读取超时管的是分片间隔_生产路径同款请求级 options（不设 timeout）_间隔超限必须有界失败")
  void readTimeoutGovernsIdleGapsInProductionShape() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "1");
    gapMillis = 3_000;
    chunkCount = 2;
    ChatModel model =
        modelFor("slow", "http://127.0.0.1:" + slowStreamServer.getAddress().getPort());
    // SpringAiProviderServiceImpl.buildPrompt 的形状：只带 model/temperature，不带 timeout
    Prompt perRequest = new Prompt("ping", OpenAiChatOptions.builder().model("slow-model").build());

    long startNanos = System.nanoTime();
    assertThatThrownBy(() -> model.stream(perRequest).collectList().block(Duration.ofSeconds(30)))
        .isInstanceOf(RuntimeException.class);
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

    assertThat(elapsedMillis).isLessThan(10_000L);
  }

  @Test
  @DisplayName("read-timeout-seconds 非正数_回落到默认 120s_不产生「读无超时 + 整次调用无墙钟」")
  void nonPositiveReadTimeoutFallsBackToDefault() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "0");
    assertThat(ProviderChatModelFactory.readTimeout()).isEqualTo(Duration.ofSeconds(120));

    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "-5");
    assertThat(ProviderChatModelFactory.readTimeout()).isEqualTo(Duration.ofSeconds(120));

    // 回落后的四项必须都为正：read/write 为 0 会让 OkHttp 关掉读超时，与 callTimeout=0 合起来就是永久挂起
    Timeout effective =
        ProviderChatModelFactory.okHttpTimeout(
            ProviderChatModelFactory.connectTimeout(), ProviderChatModelFactory.readTimeout());
    assertThat(effective.read()).isPositive();
    assertThat(effective.write()).isPositive();
    assertThat(effective.connect()).isPositive();
  }

  @Test
  @DisplayName("connect-timeout-seconds 非正数_回落到默认 10s")
  void nonPositiveConnectTimeoutFallsBackToDefault() {
    System.setProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP, "0");
    assertThat(ProviderChatModelFactory.connectTimeout()).isEqualTo(Duration.ofSeconds(10));

    System.setProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP, "-1");
    assertThat(ProviderChatModelFactory.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("read-timeout-seconds=0 且挂死端点_在生产形状下仍必须有界失败（不许永久挂起）")
  void nonPositiveReadTimeoutStillFailsBounded() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "0");
    hang = true;
    ChatModel model =
        modelFor("slow", "http://127.0.0.1:" + slowStreamServer.getAddress().getPort());
    // SpringAiProviderServiceImpl.buildPrompt 的形状：只带 model/temperature，不带 timeout
    Prompt perRequest = new Prompt("ping", OpenAiChatOptions.builder().model("slow-model").build());

    // 回落成默认 120s：必须在这之内失败，而不是永久挂起（preemptive 兜底防止测试本身挂死）
    assertTimeoutPreemptively(
        Duration.ofSeconds(140),
        () ->
            assertThatThrownBy(() -> model.call(perRequest)).isInstanceOf(RuntimeException.class));
  }

  @Test
  @DisplayName("connect-timeout-seconds 生效_连黑洞地址按配置的建连超时失败_不是 SDK 默认的 60s")
  void connectTimeoutIsHonoured() throws IOException {
    assumeTrue(blackholeDropsSyn(), BLACKHOLE_HOST + " 在本机不是黑洞（秒连或秒拒），本条无法量 connect 超时，跳过");
    System.setProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP, "2");
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "90");
    ChatModel model = modelFor("blackhole", "http://" + BLACKHOLE_HOST + ":" + BLACKHOLE_PORT);

    long startNanos = System.nanoTime();
    assertThatThrownBy(() -> model.call(new Prompt("ping"))).isInstanceOf(RuntimeException.class);
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

    // 配置 2s：同量级（含建连失败后的包装与日志），远小于 SDK 默认 PT1M 与本仓默认 10s。
    assertThat(elapsedMillis).isLessThan(8_000L);
  }

  private ChatModel modelFor(String name, String baseUrl) {
    return new ProviderChatModelFactory().buildOne(name, "sk-test", baseUrl);
  }

  /** 裸 TCP 探一下该地址是否真丢 SYN——否则这里量不出 connect 超时，跑出来的绿灯没有含义。 */
  private static boolean blackholeDropsSyn() throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(BLACKHOLE_HOST, BLACKHOLE_PORT), 1_000);
      return false; // 连上了：不是黑洞
    } catch (SocketTimeoutException timedOut) {
      return true; // 1s 无响应：SYN 被丢，正是要的场景
    } catch (IOException refusedOrUnreachable) {
      return false; // 迅速失败：量不出超时语义
    }
  }

  /** 只取有正文的分片：末条 finish_reason=stop 的收尾分片正文为空，不算一次回答。 */
  private static List<String> deltas(List<ChatResponse> chunks) {
    return chunks.stream()
        .map(chunk -> chunk.getResult().getOutput().getText())
        .filter(text -> text != null && !text.isEmpty())
        .toList();
  }

  private void streamSlowly(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    if (hang) {
      try {
        // 远长于断言窗口：必须靠客户端超时收场，不能靠服务端松手来「看起来有界」
        hangRelease.await(600, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
      return;
    }
    try (OutputStream body = exchange.getResponseBody()) {
      for (int i = 0; i < chunkCount; i++) {
        sleep(gapMillis);
        write(body, chunk(i, null));
      }
      write(body, chunk(chunkCount, "stop"));
      write(body, "data: [DONE]\n\n");
    }
  }

  private static String chunk(int index, String finishReason) {
    String delta = finishReason == null ? "{\"content\":\"chunk-" + index + "\"}" : "{}";
    String reason = finishReason == null ? "null" : "\"" + finishReason + "\"";
    return "data: {\"id\":\"chatcmpl-slow\",\"object\":\"chat.completion.chunk\",\"created\":1,"
        + "\"model\":\"slow-model\",\"choices\":[{\"index\":0,\"delta\":"
        + delta
        + ",\"finish_reason\":"
        + reason
        + "}]}\n\n";
  }

  private static void write(OutputStream body, String payload) throws IOException {
    body.write(payload.getBytes(StandardCharsets.UTF_8));
    body.flush();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
