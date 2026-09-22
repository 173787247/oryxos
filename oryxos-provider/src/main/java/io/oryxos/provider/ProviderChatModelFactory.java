package io.oryxos.provider;

import com.openai.core.Timeout;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 按全局配置逐条手工构造 ChatModel，产出显式 name→ChatModel 映射表（宪法 III）。
 *
 * <p>不使用任何 starter 自动装配（宪法 II 禁 eager 装配）；deepseek/kimi 均为 OpenAI 兼容端点，经 {@code spring-ai-openai}
 * 2.x 的 OpenAI Java SDK（{@link OpenAiChatModel} + baseUrl/apiKey）接入。
 */
public class ProviderChatModelFactory {

  /** 内置 mock provider 的保留名：配置里 {@code - name: mock} 即挂一个假模型，用于无 key 全链路自测。 */
  static final String MOCK = "mock";

  private static final String SLASH = "/";

  /** 连接超时（秒）的系统属性名：默认 10，{@code -Doryxos.llm.connect-timeout-seconds=N} 覆盖。 */
  static final String CONNECT_TIMEOUT_PROP = "oryxos.llm.connect-timeout-seconds";

  /** 读取超时（秒）的系统属性名：默认 120（推理模型长回答留足余量），{@code -Doryxos.llm.read-timeout-seconds=N} 覆盖。 */
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
    return OpenAiChatModel.builder()
        .options(options)
        .httpClientBuilderCustomizer(
            builder -> builder.timeout(okHttpTimeout(connectTimeout, readTimeout)))
        .build();
  }

  static Timeout okHttpTimeout(Duration connectTimeout, Duration readTimeout) {
    return Timeout.builder()
        .connect(connectTimeout)
        .read(readTimeout)
        .write(readTimeout)
        .request(readTimeout)
        .build();
  }

  static Duration connectTimeout() {
    return Duration.ofSeconds(Long.getLong(CONNECT_TIMEOUT_PROP, DEFAULT_CONNECT_TIMEOUT_SECONDS));
  }

  static Duration readTimeout() {
    return Duration.ofSeconds(Long.getLong(READ_TIMEOUT_PROP, DEFAULT_READ_TIMEOUT_SECONDS));
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
}
