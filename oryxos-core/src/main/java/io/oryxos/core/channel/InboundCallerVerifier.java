package io.oryxos.core.channel;

/**
 * 可选的入站调用方校验：渠道配置了 {@code extra.inbound_token} 才启用。
 *
 * <p>Google Chat 与 Teams 的官方做法都是校验平台签发的 JWT（前者由 Google 签发、后者是 Bot Framework JWT），而核心阶段不引入 OpenID /
 * JWKS 依赖 —— 官方校验由平台侧或反代承担（见 {@code specs/026-im-channel-roadmap/research.md} 的 Teams JWT
 * 决策）。这里提供的是应用层的第二道闸：一个共享 token，配了就要求调用方在 {@code Authorization: Bearer <token>} 里带上它。
 *
 * <p><b>它不是官方 JWT 校验的替代品</b>：共享 token 只能证明调用方持有该 token，无法证明它是 Google / Microsoft。反代或平台侧的签发方校验仍是正解。
 *
 * <p><b>未配置时一律放行</b>，行为与引入该闸之前逐字节一致 —— 这也是它默认不改变任何既有部署的原因。
 */
public final class InboundCallerVerifier {

  /** 渠道 {@code extra} 里的配置键：预期 token。 */
  public static final String EXTRA_INBOUND_TOKEN = "inbound_token";

  private static final String HEADER_AUTHORIZATION = "authorization";
  private static final String BEARER_PREFIX = "bearer ";

  private InboundCallerVerifier() {}

  /**
   * @param configuredToken 渠道配置里的预期 token；{@code null} 或空白 = 未启用
   * @param request 入站请求；{@code null} 视同没有 Authorization 头
   * @return 未启用或校验通过为 {@code true}
   */
  public static boolean accepts(String configuredToken, WebhookRequest request) {
    return accepts(configuredToken, request == null ? null : request.header(HEADER_AUTHORIZATION));
  }

  /**
   * @param configuredToken 渠道配置里的预期 token；{@code null} 或空白 = 未启用
   * @param authorizationHeader 收到的 {@code Authorization} 头
   * @return 未启用或校验通过为 {@code true}
   */
  public static boolean accepts(String configuredToken, String authorizationHeader) {
    if (configuredToken == null || configuredToken.isBlank()) {
      return true;
    }
    if (authorizationHeader == null) {
      return false;
    }
    String value = authorizationHeader.trim();
    if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return false;
    }
    // token 是用户自定义文本而非 hex 签名，用 UTF-8 口径的常量时间比较（ASCII 折叠会把不同的
    // 非 ASCII 文本编成同一串）。
    return SignatureComparison.constantTimeEqualsText(
        value.substring(BEARER_PREFIX.length()).trim(), configuredToken);
  }
}
