package io.oryxos.channel.weixinmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.StubHttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住客服消息出站的三层判据、报文形状与出站白名单。
 *
 * <p>与同模块的换票客户端同构：非 2xx、{@code errcode != 0}（**发生在 HTTP 200 下**）、以及本地前置校验 （token / openId 为空）。
 *
 * <p>{@link StubHttpClient} 记录请求，因此 URL、方法、正文与 Content-Type 都能断言 —— 其中 **guard 收到的必须是实际请求的那个
 * URL**，出站白名单的意义全在这一点上。
 */
class WeixinMpApiClientTest {

  private static final String TOKEN = "tok-1";

  private final List<String> guardedUrls = new ArrayList<>();

  private WeixinMpApiClient client(StubHttpClient stub) {
    return new WeixinMpApiClient(stub, guardedUrls::add, () -> TOKEN);
  }

  @Test
  @DisplayName("发送文本：URL 带 access_token、方法 POST、正文含 touser/msgtype/content")
  void sendsTextWithExpectedShape() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"errcode\":0}");

    client(stub).sendText("open-1", "你好");

    assertEquals(1, stub.requestCount());
    HttpRequest request = stub.requests().get(0);
    assertEquals("POST", request.method());
    assertEquals(
        "https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=" + TOKEN,
        request.uri().toString());

    String body = StubHttpClient.bodyOf(request);
    assertTrue(body.contains("\"touser\":\"open-1\""), body);
    assertTrue(body.contains("\"msgtype\":\"text\""), body);
    assertTrue(body.contains("\"content\":\"你好\""), body);
  }

  @Test
  @DisplayName("出站前过 guard，且校验的正是实际请求的那个 URL")
  void guardsTheUrlItActuallyRequests() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"errcode\":0}");

    client(stub).sendText("open-1", "hi");

    assertEquals(1, guardedUrls.size(), "出站前应恰好过一次 guard");
    assertEquals(
        stub.requests().get(0).uri().toString(),
        guardedUrls.get(0),
        "校验的与发出去的必须是同一个 URL —— 分叉了闸就形同虚设");
  }

  @Test
  @DisplayName("access_token 为空：本地即拒，不发请求")
  void rejectsBlankAccessToken() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"errcode\":0}");
    WeixinMpApiClient client = new WeixinMpApiClient(stub, guardedUrls::add, () -> "  ");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client.sendText("open-1", "hi"));

    assertTrue(thrown.getMessage().contains("access_token 为空"), thrown.getMessage());
    assertEquals(0, stub.requestCount(), "凭据缺失时不该打端点");
    assertEquals(0, guardedUrls.size());
  }

  @Test
  @DisplayName("openId 为空或 null：本地即拒，不发请求")
  void rejectsBlankOpenId() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"errcode\":0}");

    assertThrows(IllegalArgumentException.class, () -> client(stub).sendText("", "hi"));
    assertThrows(IllegalArgumentException.class, () -> client(stub).sendText(null, "hi"));

    assertEquals(0, stub.requestCount(), "收件人为空时不该打端点");
  }

  @Test
  @DisplayName("非 2xx 抛错并带状态码")
  void nonSuccessStatusCarriesItsCode() {
    StubHttpClient stub = StubHttpClient.always(500, "upstream boom");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).sendText("open-1", "hi"));

    assertTrue(thrown.getMessage().contains("HTTP 500"), "错误消息应点名状态码，实际是: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains("/cgi-bin/message/custom/send"), thrown.getMessage());
  }

  @Test
  @DisplayName("errcode != 0 抛错并带 errcode 与 errmsg —— 这发生在 HTTP 200 下")
  void businessErrorCarriesErrcodeAndErrmsg() {
    StubHttpClient stub =
        StubHttpClient.always(200, "{\"errcode\":45015,\"errmsg\":\"out of window\"}");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).sendText("open-1", "hi"));

    assertTrue(thrown.getMessage().contains("errcode=45015"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("out of window"), thrown.getMessage());
  }

  @Test
  @DisplayName("text 为 null：正文里 content 是空串，不是 null")
  void nullTextBecomesEmptyContent() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"errcode\":0}");

    client(stub).sendText("open-1", null);

    String body = StubHttpClient.bodyOf(stub.requests().get(0));
    assertTrue(body.contains("\"content\":\"\""), body);
    assertTrue(!body.contains("null"), "不该把 JSON null 发给平台: " + body);
  }

  @Test
  @DisplayName("access_token 里需要转义的字符被 URL 编码")
  void encodesTheAccessToken() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"errcode\":0}");
    WeixinMpApiClient client = new WeixinMpApiClient(stub, guardedUrls::add, () -> "a b&c");

    client.sendText("open-1", "hi");

    String uri = stub.requests().get(0).uri().toString();
    assertTrue(uri.contains("access_token=a+b%26c") || uri.contains("access_token=a%20b%26c"), uri);
    assertTrue(!uri.contains("&c"), "未编码的 & 会把 token 截断成查询参数: " + uri);
  }
}
