package io.oryxos.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.StubHttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住 iLink 出站的请求形状、鉴权头与出站白名单。
 *
 * <p>这个客户端比同族的其它几个多一层：除了 URL 与正文，它还带 6 个协议头（含 {@code Authorization: Bearer} 与一个每次请求都变的 {@code
 * X-WECHAT-UIN}）—— 任何一个缺失或写错，平台都会以别的错误回应，而不是直说头不对。
 *
 * <p>两个端点都要钉：{@code getupdates} 的 {@code get_updates_buf}（长轮询游标，错一次就会重复拉或漏消息）与 {@code sendmessage}
 * 的 {@code msg} 结构。
 */
class WeixinIlinkClientTest {

  private static final String TOKEN = "tok-1";
  private static final String BASE = "https://ilink.example.com";

  private final List<String> guardedUrls = new ArrayList<>();

  private WeixinIlinkClient client(StubHttpClient stub) {
    return client(stub, BASE);
  }

  private WeixinIlinkClient client(StubHttpClient stub, String baseUrl) {
    return new WeixinIlinkClient(stub, guardedUrls::add, baseUrl, () -> TOKEN);
  }

  private static HttpRequest onlyRequest(StubHttpClient stub) {
    assertEquals(1, stub.requestCount());
    return stub.requests().get(0);
  }

  @Test
  @DisplayName("getUpdates：POST 到 getupdates，带游标与 base_info，且先过 guard")
  void getUpdatesCarriesCursorAndBaseInfo() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");

    JsonNode response = client(stub).getUpdates("cursor-1", java.time.Duration.ofSeconds(5));

    HttpRequest request = onlyRequest(stub);
    assertEquals("POST", request.method());
    assertEquals(BASE + "/" + WeixinIlinkClient.EP_GET_UPDATES, request.uri().toString());
    assertEquals(List.of(request.uri().toString()), guardedUrls, "guard 应校验实际请求的 URL");

    String body = StubHttpClient.bodyOf(request);
    assertTrue(body.contains("\"get_updates_buf\":\"cursor-1\""), body);
    assertTrue(body.contains(WeixinIlinkClient.CHANNEL_VERSION), body);
    assertEquals(0, response.path("ret").asInt(-1));
  }

  @Test
  @DisplayName("sendText：msg 结构完整，item_list 里是文本项")
  void sendTextBuildsMessageBody() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");

    client(stub).sendText("user-1", "你好", "ctx-1", "client-1");

    HttpRequest request = onlyRequest(stub);
    assertEquals(BASE + "/" + WeixinIlinkClient.EP_SEND_MESSAGE, request.uri().toString());

    String body = StubHttpClient.bodyOf(request);
    assertTrue(body.contains("\"to_user_id\":\"user-1\""), body);
    assertTrue(body.contains("\"client_id\":\"client-1\""), body);
    assertTrue(body.contains("\"context_token\":\"ctx-1\""), body);
    assertTrue(body.contains("\"text\":\"你好\""), body);
    assertTrue(body.contains("item_list"), body);
  }

  @Test
  @DisplayName("sendText：contextToken 为空时不带该字段（而不是带空串）")
  void omitsBlankContextToken() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");

    client(stub).sendText("user-1", "hi", "  ", "client-1");

    String body = StubHttpClient.bodyOf(onlyRequest(stub));
    assertTrue(!body.contains("context_token"), "空 context_token 不该出现过: " + body);
  }

  @Test
  @DisplayName("协议头齐全：Bearer 鉴权、App-Id、版本、以及随机的 X-WECHAT-UIN")
  void sendsProtocolHeaders() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");

    client(stub).getUpdates("", java.time.Duration.ofSeconds(5));

    HttpRequest request = onlyRequest(stub);
    assertEquals("Bearer " + TOKEN, header(request, "Authorization"));
    assertEquals("ilink_bot_token", header(request, "AuthorizationType"));
    assertEquals("bot", header(request, "iLink-App-Id"));
    assertEquals(
        Integer.toString(WeixinIlinkClient.APP_CLIENT_VERSION),
        header(request, "iLink-App-ClientVersion"));
    assertEquals("application/json", header(request, "Content-Type"));
    assertTrue(header(request, "X-WECHAT-UIN") != null, "X-WECHAT-UIN 必须在");
  }

  @Test
  @DisplayName("X-WECHAT-UIN 每次请求都不同")
  void wechatUinVariesPerRequest() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");
    WeixinIlinkClient client = client(stub);

    client.getUpdates("", java.time.Duration.ofSeconds(5));
    client.getUpdates("", java.time.Duration.ofSeconds(5));

    assertEquals(2, stub.requestCount());
    assertNotEquals(
        header(stub.requests().get(0), "X-WECHAT-UIN"),
        header(stub.requests().get(1), "X-WECHAT-UIN"),
        "该头是随机值，两次请求相同说明生成逻辑坏了");
  }

  @Test
  @DisplayName("token 为空：本地即拒，不发请求")
  void rejectsBlankToken() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");
    WeixinIlinkClient blank = new WeixinIlinkClient(stub, guardedUrls::add, BASE, () -> "  ");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> blank.getUpdates("", java.time.Duration.ofSeconds(5)));

    assertTrue(thrown.getMessage().contains("token 为空"), thrown.getMessage());
    assertEquals(0, stub.requestCount(), "凭据缺失时不该打端点");
    assertEquals(0, guardedUrls.size());
  }

  @Test
  @DisplayName("baseUrl 的尾斜杠被规范化，不会拼出双斜杠")
  void trimsTrailingSlash() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");

    client(stub, BASE + "/").getUpdates("", java.time.Duration.ofSeconds(5));

    assertEquals(BASE + "/" + WeixinIlinkClient.EP_GET_UPDATES, onlyRequest(stub).uri().toString());
  }

  @Test
  @DisplayName("baseUrl 缺省时回落到官方域名")
  void fallsBackToDefaultBase() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"ret\":0}");

    client(stub, "  ").getUpdates("", java.time.Duration.ofSeconds(5));

    assertTrue(
        onlyRequest(stub).uri().toString().startsWith(WeixinIlinkClient.DEFAULT_BASE),
        onlyRequest(stub).uri().toString());
  }

  @Test
  @DisplayName("非 2xx 抛错并带状态码")
  void nonSuccessStatusCarriesItsCode() throws Exception {
    StubHttpClient stub = StubHttpClient.always(503, "upstream down");
    WeixinIlinkClient client = client(stub);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> client.getUpdates("", java.time.Duration.ofSeconds(5)));

    assertTrue(thrown.getMessage().contains("HTTP 503"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("upstream down"), thrown.getMessage());
  }

  private static String header(HttpRequest request, String name) {
    return request.headers().firstValue(name).orElse(null);
  }
}
