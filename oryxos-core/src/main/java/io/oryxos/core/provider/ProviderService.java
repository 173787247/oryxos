package io.oryxos.core.provider;

import io.oryxos.core.profile.Profile;

/**
 * Provider 调用契约：按 Profile 路由到对应模型、发起一次调用、结果原样返回。
 *
 * <p>契约放 core 是依赖倒置（宪法 v1.1.0 模块条款）：ReActLoop（core）只认此接口， 具体协议转换由 oryxos-provider 的 {@code
 * SpringAiProviderServiceImpl} 实现——core 不依赖任何 Spring AI 类型。
 */
public interface ProviderService {

  /**
   * 发起一次模型调用；不论成败都落 llm_calls 审计（宪法 V），失败先落账再上抛。
   *
   * @param sessionId 会话标识，审计按 session 关联（只透传，不在此生成）
   */
  ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);

  /**
   * 流式模型调用（019）：回复文本增量经 {@code onToken} 同步逐段回调（只回调 content 增量，不含 tool-call
   * 增量），返回值仍是完整响应（文本/toolCalls/usage）。审计口径与 {@link #chat} 完全一致。
   *
   * <p>默认实现即「无流式能力的降级」（FR-006）：整段调用 {@link #chat} 后把全文一次性回调——实现方有真流式 能力时覆写本方法；流式获取失败且结果残缺时 MUST
   * 上抛而非把残缺内容当完整返回。
   */
  default ProviderResponse chatStream(
      String sessionId,
      Profile profile,
      ProviderRequest request,
      java.util.function.Consumer<String> onToken) {
    ProviderResponse response = chat(sessionId, profile, request);
    if (response.text() != null && !response.text().isEmpty()) {
      onToken.accept(response.text());
    }
    return response;
  }
}
