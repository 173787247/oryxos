package io.oryxos.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.channel.StubHttpClient;
import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 覆盖换票这一层。
 *
 * <p>依据是官方「获取访问凭证」页（{@code bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html}）：
 * 该接口的业务错误走响应体的 {@code code}，<b>失败时 HTTP 返回码仍是 200</b>，官方明确要求「优先依据 code 判断请求是否成功，不要只依赖 HTTP
 * 返回码」，并且「不要依据 message 判定错误类型」。
 *
 * <p>所以这里钉的不是 HTTP 状态码分支（在那个端点上它永远进不去），而是 <b>code → 可读错误</b> 这一层：换票失败时， 排障的人应当直接看到「AppID 或
 * ClientSecret 不正确」，而不是一条把官方 message 埋在 JSON 里的 「无 access_token」。
 */
class QqAccessTokenClientTest {

  private static final OutboundGuard ALLOW_ALL = url -> {};

  @Test
  @DisplayName("官方失败响应（HTTP 200 + code）报出错误码与排查提示，而不是「无 access_token」")
  void businessErrorIsReportedByCode() {
    StubHttpClient http =
        StubHttpClient.always(200, "{\"code\":100016,\"message\":\"invalid appid or secret\"}");
    QqAccessTokenClient client = new QqAccessTokenClient(http, ALLOW_ALL, "app-id", "secret");

    IllegalStateException failure = assertThrows(IllegalStateException.class, client::accessToken);

    String message = failure.getMessage();
    assertTrue(message.contains("code=100016"), message);
    assertTrue(message.contains("ClientSecret 不正确"), message);
    assertTrue(message.contains("invalid appid or secret"), message);
    assertFalse(message.contains("无 access_token"), "业务错误不应被报成缺字段: " + message);
  }

  @Test
  @DisplayName("官方列出的四个业务错误码各有对应排查指南")
  void documentedCodesCarryTheirGuidance() {
    assertTrue(describe(100001).contains("请求过于频繁，请降低调用频率后重试"), describe(100001));
    assertTrue(describe(100007).contains("AppID 无效，或机器人状态不正常（被封禁或已删除）"), describe(100007));
    assertTrue(describe(100016).contains("AppID 或 ClientSecret 不正确"), describe(100016));
    assertTrue(describe(10004).contains("AppID 对应的机器人不存在"), describe(10004));
  }

  @Test
  @DisplayName("官方表里没有的错误码原样报出，不猜含义")
  void unknownCodeIsReportedAsUnknown() {
    assertTrue(describe(100999).contains("官方文档未列出该错误码"), describe(100999));
    assertTrue(describe(100999).contains("100999"), describe(100999));
  }

  @Test
  @DisplayName("code 非 0 时不使用响应里的 access_token")
  void nonZeroCodeWinsOverAStrayToken() {
    StubHttpClient http =
        StubHttpClient.always(
            200, "{\"code\":100007,\"message\":\"appid invalid\",\"access_token\":\"stray\"}");
    QqAccessTokenClient client = new QqAccessTokenClient(http, ALLOW_ALL, "app-id", "secret");

    IllegalStateException failure = assertThrows(IllegalStateException.class, client::accessToken);

    assertTrue(failure.getMessage().contains("code=100007"), failure.getMessage());
  }

  @Test
  @DisplayName("厂商 message 里的换行被压平，不把一条日志拆成多行")
  void vendorMessageIsFlattened() {
    StubHttpClient http =
        StubHttpClient.always(200, "{\"code\":100016,\"message\":\"line1\\nline2\"}");
    QqAccessTokenClient client = new QqAccessTokenClient(http, ALLOW_ALL, "app-id", "secret");

    String message = assertThrows(IllegalStateException.class, client::accessToken).getMessage();

    assertFalse(message.contains("\n"), message);
    assertTrue(message.contains("line1_line2"), message);
  }

  @Test
  @DisplayName("成功响应取到 token，鉴权头是官方要求的 QQBot 前缀")
  void successYieldsAuthorizationHeader() {
    StubHttpClient http =
        StubHttpClient.always(200, "{\"access_token\":\"TOKEN\",\"expires_in\":\"7200\"}");
    QqAccessTokenClient client = new QqAccessTokenClient(http, ALLOW_ALL, "app-id", "secret");

    assertEquals("QQBot TOKEN", client.authorizationHeader());
  }

  @Test
  @DisplayName("有效期内不重复换票")
  void tokenIsCachedWithinItsValidity() {
    StubHttpClient http =
        StubHttpClient.always(200, "{\"access_token\":\"TOKEN\",\"expires_in\":\"7200\"}");
    QqAccessTokenClient client = new QqAccessTokenClient(http, ALLOW_ALL, "app-id", "secret");

    client.accessToken();
    client.accessToken();

    assertEquals(1, http.requestCount(), "第二次调用应命中缓存");
  }

  @Test
  @DisplayName("换票请求打官方端点，方法为 POST")
  void requestShapeMatchesTheDocumentedEndpoint() {
    StubHttpClient http =
        StubHttpClient.always(200, "{\"access_token\":\"TOKEN\",\"expires_in\":\"7200\"}");
    QqAccessTokenClient client = new QqAccessTokenClient(http, ALLOW_ALL, "app-id", "secret");

    client.accessToken();

    HttpRequest request = http.requests().get(0);
    assertEquals("POST", request.method());
    assertEquals(URI.create("https://api.bot.qq.com/app/getAppAccessToken"), request.uri());
  }

  @Test
  @DisplayName("白名单拦下时不发请求")
  void guardRejectionStopsBeforeTheRequest() {
    StubHttpClient http =
        StubHttpClient.always(200, "{\"access_token\":\"TOKEN\",\"expires_in\":\"7200\"}");
    OutboundGuard denyAll =
        url -> {
          throw new IllegalStateException("写请求(POST/PUT 等)目标不在出网白名单: " + url);
        };
    QqAccessTokenClient client = new QqAccessTokenClient(http, denyAll, "app-id", "secret");

    IllegalStateException failure = assertThrows(IllegalStateException.class, client::accessToken);

    assertTrue(failure.getMessage().contains("出网白名单"), failure.getMessage());
    assertEquals(0, http.requestCount());
  }

  /** 只关心错误文案时用：借一次失败请求把 message 取出来。 */
  private static String describe(int code) {
    StubHttpClient http =
        StubHttpClient.always(200, "{\"code\":" + code + ",\"message\":\"vendor text\"}");
    QqAccessTokenClient client = new QqAccessTokenClient(http, ALLOW_ALL, "app-id", "secret");
    return assertThrows(IllegalStateException.class, client::accessToken).getMessage();
  }
}
