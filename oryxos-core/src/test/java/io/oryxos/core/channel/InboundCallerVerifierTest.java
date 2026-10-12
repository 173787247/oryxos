package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住入站调用方校验的三条边界：未配置一律放行、配置后只认 Bearer、以及非 ASCII token 不被 ASCII 折叠。
 *
 * <p>最后一条是选用 {@code constantTimeEqualsText}（UTF-8）而不是 {@code constantTimeEquals}（US_ASCII）的 原因：后者把非
 * ASCII 折成 {@code ?}，会让配置里的 {@code "?"} 与提交的 {@code "ａ"} 编成同一串而互相命中。
 */
class InboundCallerVerifierTest {

  private static final String CONFIGURED = "s3cret-token";

  private static WebhookRequest withAuthorization(String value) {
    // Map.of 不接受 null 值；"没有这个头"用空 map 表达，与真实请求一致。
    Map<String, String> headers = value == null ? Map.of() : Map.of("authorization", value);
    return new WebhookRequest("POST", Map.of(), headers, "{}");
  }

  // ---------- 未配置：行为必须与引入前一致 ----------

  @Test
  @DisplayName("未配置 token 时一律放行，包括毫无凭据的请求")
  void unconfiguredAcceptsEverything() {
    assertTrue(InboundCallerVerifier.accepts(null, withAuthorization(null)), "null 配置应放行");
    assertTrue(InboundCallerVerifier.accepts("", withAuthorization(null)), "空配置应放行");
    assertTrue(InboundCallerVerifier.accepts("   ", withAuthorization(null)), "空白配置应放行");
    assertTrue(
        InboundCallerVerifier.accepts("", withAuthorization("Bearer whatever")), "未配置时不该看凭据");
    assertTrue(InboundCallerVerifier.accepts(null, (WebhookRequest) null), "null 配置 + null 请求应放行");
  }

  // ---------- 配置后：只认 Authorization: Bearer <token> ----------

  @Test
  @DisplayName("配置后，正确的 Bearer token 放行")
  void acceptsMatchingBearerToken() {
    assertTrue(
        InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("Bearer " + CONFIGURED)));
  }

  @Test
  @DisplayName("配置后，错误的 token 拒绝")
  void rejectsWrongToken() {
    assertFalse(InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("Bearer nope")));
    assertFalse(
        InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("Bearer " + CONFIGURED + "x")),
        "前缀命中的更长串不应被接受");
  }

  @Test
  @DisplayName("配置后，缺 Authorization 头拒绝 —— 这是 issue #918 的复现形态")
  void rejectsMissingHeader() {
    assertFalse(InboundCallerVerifier.accepts(CONFIGURED, withAuthorization(null)));
    assertFalse(
        InboundCallerVerifier.accepts(
            CONFIGURED, new WebhookRequest("POST", Map.of(), Map.of(), "{}")));
  }

  @Test
  @DisplayName("配置后，非 Bearer 方案拒绝（含裸 token、Basic、空方案）")
  void rejectsOtherSchemes() {
    assertFalse(InboundCallerVerifier.accepts(CONFIGURED, withAuthorization(CONFIGURED)));
    assertFalse(
        InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("Basic " + CONFIGURED)));
    assertFalse(InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("Bearer")));
    assertFalse(InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("Bearer ")));
  }

  @Test
  @DisplayName("Bearer 方案大小写不敏感，token 前后空白容忍")
  void schemeIsCaseInsensitive() {
    assertTrue(
        InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("bearer " + CONFIGURED)));
    assertTrue(
        InboundCallerVerifier.accepts(CONFIGURED, withAuthorization("BEARER " + CONFIGURED)));
    assertTrue(
        InboundCallerVerifier.accepts(
            CONFIGURED, withAuthorization("  Bearer " + CONFIGURED + "  ")));
  }

  // ---------- token 是用户自定义文本，不是 hex ----------

  @Test
  @DisplayName("非 ASCII token 不按 ASCII 折叠：配置 \"?\" 不会被提交 \"ａ\" 命中")
  void doesNotFoldNonAscii() {
    assertFalse(
        InboundCallerVerifier.accepts("?", withAuthorization("Bearer \uFF41")),
        "UTF-8 口径下 U+FF41 与 ? 是不同字节；ASCII 折叠会让这两个互相命中");
    assertTrue(
        InboundCallerVerifier.accepts("\u4ee4\u724c", withAuthorization("Bearer \u4ee4\u724c")),
        "配置与提交同样的非 ASCII token 应放行");
  }
}
