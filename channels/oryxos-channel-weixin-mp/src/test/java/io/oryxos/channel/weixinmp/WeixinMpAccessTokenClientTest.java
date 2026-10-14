package io.oryxos.channel.weixinmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.StubHttpClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住换票的三层判据与缓存行为。
 *
 * <p>{@code /cgi-bin/token} 的失败有三种形态，且第二种发生在 HTTP 200 下：非 2xx、{@code errcode != 0} （业务错误走 body）、以及
 * 200 + {@code errcode:0} 却没给 {@code access_token}。
 *
 * <p>用 {@link StubHttpClient}（{@code oryxos-core} 的 test-jar）：它记录请求，因此「第二次命中缓存不再打端点」
 * 可以直接断言请求数。构造器本来就接受注入的 {@code HttpClient}，没有为测试改动生产代码。
 */
class WeixinMpAccessTokenClientTest {

  private final List<String> guardedUrls = new ArrayList<>();

  private WeixinMpAccessTokenClient client(StubHttpClient stub) {
    return new WeixinMpAccessTokenClient(stub, guardedUrls::add, "app-id-1", "secret-1");
  }

  private static StubHttpClient ok(String body) {
    return StubHttpClient.always(200, body);
  }

  @Test
  @DisplayName("2xx + errcode 0 → 返回 access_token")
  void returnsToken() {
    StubHttpClient stub = ok("{\"errcode\":0,\"access_token\":\"tok-1\",\"expires_in\":7200}");
    assertEquals("tok-1", client(stub).getToken());
  }

  @Test
  @DisplayName("取到的 token 被缓存：第二次不再打端点")
  void cachesToken() {
    StubHttpClient stub = ok("{\"errcode\":0,\"access_token\":\"tok-1\",\"expires_in\":7200}");
    WeixinMpAccessTokenClient client = client(stub);

    assertEquals("tok-1", client.getToken());
    assertEquals("tok-1", client.getToken());
    assertEquals(1, stub.requestCount(), "第二次取 token 应命中缓存，不应再次请求");
  }

  @Test
  @DisplayName("非 2xx 抛错并在消息里带上状态码")
  void nonSuccessStatusCarriesItsCode() {
    StubHttpClient stub = StubHttpClient.always(500, "upstream boom");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).getToken());

    assertTrue(
        thrown.getMessage().contains("服务号 token HTTP 500"),
        "错误消息应点名状态码，实际是: " + thrown.getMessage());
  }

  @Test
  @DisplayName("errcode != 0 抛错并带上 errcode 与 errmsg —— 这发生在 HTTP 200 下")
  void businessErrorCarriesErrcodeAndErrmsg() {
    StubHttpClient stub = ok("{\"errcode\":40013,\"errmsg\":\"invalid appid\"}");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).getToken());

    assertTrue(thrown.getMessage().contains("errcode=40013"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("invalid appid"), thrown.getMessage());
  }

  @Test
  @DisplayName("2xx 且 errcode 0 却没给 access_token → 点名报错")
  void missingTokenIsReported() {
    StubHttpClient stub = ok("{\"errcode\":0}");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).getToken());

    assertTrue(thrown.getMessage().contains("未返回 access_token"), thrown.getMessage());
  }

  @Test
  @DisplayName("请求 URL 形状正确（appid/secret），且先过 OutboundGuard")
  void buildsGuardedUrl() {
    StubHttpClient stub = ok("{\"errcode\":0,\"access_token\":\"t\"}");
    client(stub).getToken();

    assertEquals(1, stub.requestCount());
    String uri = stub.requests().get(0).uri().toString();
    assertTrue(
        uri.startsWith("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"),
        uri);
    assertTrue(uri.contains("appid=app-id-1"), uri);
    assertTrue(uri.contains("secret=secret-1"), uri);

    assertEquals(1, guardedUrls.size(), "出站前应恰好过一次 guard");
    assertEquals(uri, guardedUrls.get(0), "guard 校验的应是实际请求的那个 URL");
  }

  @Test
  @DisplayName("expires_in 缺失时按默认 7200 秒缓存，不会立刻过期重取")
  void missingExpiresInFallsBackToDefaultTtl() {
    StubHttpClient stub = ok("{\"errcode\":0,\"access_token\":\"t\"}");
    WeixinMpAccessTokenClient client = client(stub);

    client.getToken();
    client.getToken();
    assertEquals(1, stub.requestCount(), "默认 TTL 应足够长，第二次仍命中缓存");
  }
}
