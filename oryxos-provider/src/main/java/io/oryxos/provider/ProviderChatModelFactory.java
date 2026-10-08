package io.oryxos.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * 按全局配置逐条手工构造 ChatModel，产出显式 name→ChatModel 映射表（宪法 III）。
 *
 * <p>不使用任何 starter 自动装配（宪法 II 禁 eager 装配）；deepseek/kimi 均为 OpenAI 兼容端点，经 {@code spring-ai-openai}
 * 2.x 的 OpenAI Java SDK（{@link OpenAiChatModel} + baseUrl/apiKey）接入。
 */
public class ProviderChatModelFactory {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderChatModelFactory.class);

  /** 内置 mock provider 的保留名：配置里 {@code - name: mock} 即挂一个假模型，用于无 key 全链路自测。 */
  static final String MOCK = "mock";

  private static final String SLASH = "/";

  /** 连接超时（秒）的系统属性名：默认 10，{@code -Doryxos.llm.connect-timeout-seconds=N} 覆盖；非正数回落默认值并告警。 */
  static final String CONNECT_TIMEOUT_PROP = "oryxos.llm.connect-timeout-seconds";

  /**
   * 读取超时（秒）的系统属性名：默认 120（推理模型长回答留足余量），{@code -Doryxos.llm.read-timeout-seconds=N} 覆盖；非正数回落默认值并告警。
   */
  static final String READ_TIMEOUT_PROP = "oryxos.llm.read-timeout-seconds";

  private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final long DEFAULT_READ_TIMEOUT_SECONDS = 120;

  /**
   * 末尾版本段（如 /v1、GLM 的 /api/paas/v4）：OpenAI Java SDK 的 baseUrl 已含版本，路径相对追加 {@code
   * /chat/completions}，不再像 AI 1.x OpenAiApi 那样内部再插 /v1。
   */
  private static final Pattern TRAILING_VERSION = Pattern.compile(".*/v\\d+$");

  public Map<String, ChatModel> build(ProvidersProperties properties) {
    properties.validate();
    Map<String, ChatModel> providerMap = new LinkedHashMap<>();
    for (ProvidersProperties.ProviderConfig config : properties.providers()) {
      providerMap.put(config.name(), buildOne(config.name(), config.apiKey(), config.baseUrl()));
    }
    return providerMap;
  }

  /** 按单个 provider 的参数手工构造 ChatModel（31 节动态 provider：按名从注册表取参数后即时建）。mock 名走内置假模型。 */
  public ChatModel buildOne(String name, String apiKey, String baseUrl) {
    if (MOCK.equals(name)) {
      return new MockChatModel(); // 不连真实端点，无需 key/url
    }
    String base = normalizeOpenAiBaseUrl(baseUrl);
    Duration connectTimeout = connectTimeout();
    Duration readTimeout = readTimeout();
    // 023 R8：maxRetries=0，重试语义整层上收到 fallback 切换循环（单层负责）
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .baseUrl(base)
            .apiKey(apiKey)
            .timeout(readTimeout)
            .maxRetries(0)
            .build();
    OpenAIClient client = sdkClient(base, apiKey, connectTimeout, readTimeout);
    return OpenAiChatModel.builder()
        .openAiClient(client)
        .openAiClientAsync(client.async())
        .options(options)
        .build();
  }

  /**
   * 手工构造 OpenAI Java SDK 客户端；超时由 {@link TimeoutPinnedHttpClient} 逐请求钉死，不走 Spring AI 的超时折叠。
   *
   * <p>Spring AI 2.0.1 把 options 里的 Duration 折叠成「整请求」超时（{@code
   * RequestOptions.Builder.timeout(Duration)} → {@code
   * Timeout.builder().request(d)}，read/write/request 全等于它，connect 缺省 SDK 的 PT1M），而 {@code
   * SpringAiOpenAiHttpClient.newCall} 每次请求都用这个 Timeout 重建 OkHttp 的 connect/read/write/call 四项——所以
   * {@code httpClientBuilderCustomizer} 里设的超时永远被后写覆盖，且 OkHttp 的 callTimeout
   * （整次调用的墙钟上限）会等于读取超时，健康的长流式回答到点即被掐断。
   */
  static OpenAIClient sdkClient(
      String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
    Timeout timeout = okHttpTimeout(connectTimeout, readTimeout);
    HttpClient transport =
        new TimeoutPinnedHttpClient(
            SpringAiOpenAiHttpClient.builder().timeout(timeout).build(), timeout);
    ClientOptions sdkOptions =
        ClientOptions.builder()
            .httpClient(transport)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .maxRetries(0) // 023 R8：重试只由 fallback 层负责
            .timeout(timeout) // 客户端级兜底；有逐请求钉值，正常路径不取这里
            .build();
    return new OpenAIClientImpl(sdkOptions);
  }

  /**
   * connect 管建连、read/write 管「多久没读到 / 写不动算挂」，request 置 0 表示不设整次调用的墙钟上限。
   *
   * <p>三者必须分开：流式回答的总时长本就可以远超读取超时，只要分片之间的间隔没超——把总时长当上限会掐断健康回答， 把上限去掉又会让挂死端点永久阻塞。
   */
  static Timeout okHttpTimeout(Duration connectTimeout, Duration readTimeout) {
    return Timeout.builder()
        .connect(connectTimeout)
        .read(readTimeout)
        .write(readTimeout)
        .request(Duration.ZERO)
        .build();
  }

  static Duration connectTimeout() {
    return positiveSeconds(CONNECT_TIMEOUT_PROP, DEFAULT_CONNECT_TIMEOUT_SECONDS);
  }

  static Duration readTimeout() {
    return positiveSeconds(READ_TIMEOUT_PROP, DEFAULT_READ_TIMEOUT_SECONDS);
  }

  /**
   * 非正数一律回落到默认值并告警，绝不放行 0。
   *
   * <p>OkHttp 把 {@code connectTimeout(0)} / {@code readTimeout(0)} / {@code callTimeout(0)}
   * 都解释成「不设该上限」； 读取超时与整次调用墙钟同时为 0，挂死端点就会永久阻塞——一条配置（哪怕手滑多打一个 0）足以让护栏失效，所以不提供 「0 = 显式不限」这个语义。
   */
  private static Duration positiveSeconds(String property, long fallbackSeconds) {
    long seconds = Long.getLong(property, fallbackSeconds);
    if (seconds <= 0) {
      LOG.warn(
          "{}={} 非正数，回落到默认 {}s：0 会让 OkHttp 关掉对应上限，读超时与整次调用墙钟同时为 0 时挂死端点将永久阻塞",
          sanitizeLogValue(property),
          seconds,
          fallbackSeconds);
      return Duration.ofSeconds(fallbackSeconds);
    }
    return Duration.ofSeconds(seconds);
  }

  /** 日志里的外部字符串一律过一遍：CR/LF 能伪造日志行（与 SpringAiProviderServiceImpl.sanitize 同款）。 */
  private static String sanitizeLogValue(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  /**
   * OpenAI Java SDK 期望 baseUrl 含版本段（默认 {@code https://api.openai.com/v1}）。用户填了 /vN 则保留；否则补 {@code
   * /v1}。与 AI 1.x stripTrailingV1 相反——那时 OpenAiApi 内部会再插 /v1。
   */
  static String normalizeOpenAiBaseUrl(String baseUrl) {
    String u = baseUrl == null ? "" : baseUrl.strip();
    while (u.endsWith(SLASH)) {
      u = u.substring(0, u.length() - SLASH.length());
    }
    if (u.isEmpty()) {
      return u;
    }
    if (TRAILING_VERSION.matcher(u).matches()) {
      return u;
    }
    return u + "/v1";
  }

  /**
   * 把 SDK 每次请求自带的 Timeout 换成 {@link #okHttpTimeout} 钉死的那一份。
   *
   * <p>SDK 的服务层把 {@code RequestOptions} 原样交给 HttpClient；Spring AI 总会往里塞一个由单个 Duration 折叠出来的
   * Timeout。这里在最后一跳改写，OkHttp 才不会拿到「整请求 = 读取超时、connect = PT1M」的配置。
   */
  private static final class TimeoutPinnedHttpClient implements HttpClient {

    private final HttpClient delegate;

    private final Timeout timeout;

    TimeoutPinnedHttpClient(HttpClient delegate, Timeout timeout) {
      this.delegate = delegate;
      this.timeout = timeout;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
      return delegate.execute(request, pinned());
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(
        HttpRequest request, RequestOptions requestOptions) {
      return delegate.executeAsync(request, pinned());
    }

    @Override
    public void close() {
      delegate.close();
    }

    /** 传进来的 Timeout 一律丢弃（它只有整请求语义）；其余字段本来就只有 responseValidation，留空即取客户端默认。 */
    private RequestOptions pinned() {
      return RequestOptions.builder().timeout(timeout).build();
    }
  }
}
