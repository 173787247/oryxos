package io.oryxos.provider;

import io.oryxos.core.embedding.TextEmbedder;
import java.util.List;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.client.RestClient;

/**
 * 按 Provider 注册表参数手工构造 {@link TextEmbedder}（FR-007：复用同一套凭证，不另建配置）。 复用与 ChatModel 完全相同的 {@link
 * OpenAiApi} 构建链（stripTrailingV1 / HTTP1.1 / 超时工厂， research D2）；mock 名走内置确定性向量。
 *
 * <p>embedding provider 未配置时不静默回退（DeepSeek 等无 embedding 端点，「取第一个可用项」会 静默拿到错误 provider）——调用方拿到可读异常后按
 * FR-013 走降级或报错（plan 停点 3）。
 */
public class ProviderEmbeddingModelFactory {

  private static final String SLASH = "/";
  private static final String PATH_V1 = "/v1";

  private static final java.util.regex.Pattern TRAILING_VERSION =
      java.util.regex.Pattern.compile(".*/v\\d+$");

  /** 按单个 provider 参数即时构造；参数缺失给可读错误，不猜测。 */
  public TextEmbedder buildOne(String name, String apiKey, String baseUrl, String model) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "未配置 embedding provider（knowledge.embedding.provider）：向量化不可用，"
              + "请配置支持 embedding 端点的 provider（如 qwen）或 mock");
    }
    if (ProviderChatModelFactory.MOCK.equals(name)) {
      return new MockEmbeddingModel();
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException(
          "未配置 embedding 模型（knowledge.embedding.model），provider: " + name);
    }
    if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("Provider 缺少 api_key / base_url，无法构造 embedding: " + name);
    }
    String base = stripTrailingV1(baseUrl);
    OpenAiApi.Builder api =
        OpenAiApi.builder()
            .baseUrl(base)
            .apiKey(apiKey)
            .restClientBuilder(
                RestClient.builder().requestFactory(ProviderChatModelFactory.timeoutFactory()));
    if (TRAILING_VERSION.matcher(base).matches()) {
      // 端点版本已在 baseUrl 里（如 /api/paas/v4），改补无版本的 /embeddings（与 completionsPath 同理）
      api.embeddingsPath("/embeddings");
    }
    OpenAiEmbeddingModel delegate =
        new OpenAiEmbeddingModel(
            api.build(), MetadataMode.EMBED, OpenAiEmbeddingOptions.builder().model(model).build());
    return new SpringAiTextEmbedder(delegate, name + "/" + model);
  }

  private static String stripTrailingV1(String baseUrl) {
    String u = baseUrl == null ? "" : baseUrl.strip();
    while (u.endsWith(SLASH) || u.endsWith(PATH_V1)) {
      u =
          u.endsWith(SLASH)
              ? u.substring(0, u.length() - SLASH.length())
              : u.substring(0, u.length() - PATH_V1.length());
    }
    return u;
  }

  /** Spring AI 只做协议转换（宪法 II）：包一层适配到 core 的 TextEmbedder 端口。 */
  static final class SpringAiTextEmbedder implements TextEmbedder {

    private final OpenAiEmbeddingModel delegate;
    private final String modelId;
    private volatile int dimensions;

    SpringAiTextEmbedder(OpenAiEmbeddingModel delegate, String modelId) {
      this.delegate = delegate;
      this.modelId = modelId;
    }

    @Override
    public float[] embed(String text) {
      float[] vector = delegate.embed(text);
      dimensions = vector.length;
      return vector;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      List<float[]> vectors = delegate.embed(texts);
      if (!vectors.isEmpty()) {
        dimensions = vectors.get(0).length;
      }
      return vectors;
    }

    @Override
    public String modelId() {
      return modelId;
    }

    /** 维度由首次调用的真实返回确定（不同 provider/model 维度不同，不做硬编码）。 */
    @Override
    public int dimensions() {
      return dimensions;
    }
  }
}
