package io.oryxos.memory;

import io.oryxos.core.embedding.TextEmbedder;
import java.util.function.Supplier;

/**
 * 延迟解析的 embedder 包装（015 装配用）：每次调用才向供给者取真实实现——配置错误（provider 不存在、
 * 凭证缺失）不会阻断启动，而是在检索/索引的调用点抛可读异常，由引擎降级（FR-003）与索引静默重试（FR-005）消化。
 */
public class DeferredTextEmbedder implements TextEmbedder {

  private final Supplier<TextEmbedder> delegate;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "供给者为装配注入的共享协作者，构造注入存同一引用正是意图")
  public DeferredTextEmbedder(Supplier<TextEmbedder> delegate) {
    this.delegate = delegate;
  }

  @Override
  public float[] embed(String text) {
    return delegate.get().embed(text);
  }

  @Override
  public String modelId() {
    return delegate.get().modelId();
  }

  @Override
  public int dimensions() {
    return delegate.get().dimensions();
  }
}
