package io.oryxos.channel.alipay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.StubHttpClient;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住生活号客服消息出站的判据、报文形状与出站白名单。
 *
 * <p>支付宝的失败有三种形态：非 2xx、**业务码非 {@code 10000}（发生在 HTTP 200 下）**，以及本地前置校验
 * （收件人为空）。业务码那条还分带包裹与不带包裹两种响应形状 —— 网关通常包一层 {@code
 * alipay_open_public_message_custom_send_response}，但代码对两者都处理，所以两种都钉。
 *
 * <p>签名用真实 RSA 密钥（照 {@code AlipayChannelAdapterTest} 的做法现生成），因此断言的是**真的被签过**， 而不是一个探针。
 */
class AlipayApiClientTest {

  private static final String APP_ID = "2014072300007148";

  private final List<String> guardedUrls = new ArrayList<>();

  private AlipayRsa2 rsa;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair app = generator.generateKeyPair();
    KeyPair alipay = generator.generateKeyPair();
    rsa =
        new AlipayRsa2(
            Base64.getEncoder().encodeToString(app.getPrivate().getEncoded()),
            Base64.getEncoder().encodeToString(alipay.getPublic().getEncoded()),
            Base64.getEncoder().encodeToString(app.getPublic().getEncoded()));
  }

  private AlipayApiClient client(StubHttpClient stub) {
    return new AlipayApiClient(stub, guardedUrls::add, APP_ID, rsa);
  }

  private static String formValue(String body, String key) {
    for (String pair : body.split("&")) {
      int split = pair.indexOf('=');
      if (split > 0
          && URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8).equals(key)) {
        return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("表单里没有 " + key + "；实际: " + body);
  }

  @Test
  @DisplayName("发送文本：POST 表单带 app_id/method/biz_content/sign，且先过 guard")
  void sendsSignedForm() throws Exception {
    StubHttpClient stub = StubHttpClient.always(200, "{\"code\":\"10000\"}");

    client(stub).sendText("user-1", "你好");

    assertEquals(1, stub.requestCount());
    HttpRequest request = stub.requests().get(0);
    assertEquals("POST", request.method());
    assertEquals(AlipayApiClient.API_BASE, request.uri().toString());

    String body = StubHttpClient.bodyOf(request);
    assertEquals(APP_ID, formValue(body, "app_id"));
    assertEquals(AlipayApiClient.METHOD_CUSTOM_SEND, formValue(body, "method"));
    assertEquals("RSA2", formValue(body, "sign_type"));
    assertTrue(!formValue(body, "sign").isBlank(), "必须带签名");

    String biz = formValue(body, "biz_content");
    assertTrue(biz.contains("\"to_user_id\":\"user-1\""), biz);
    assertTrue(biz.contains("\"msg_type\":\"text\""), biz);
    assertTrue(biz.contains("\"content\":\"你好\""), biz);
  }

  @Test
  @DisplayName("出站前过 guard，校验的是请求目标")
  void guardsTheEndpoint() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"code\":\"10000\"}");

    client(stub).sendText("user-1", "hi");

    assertEquals(List.of(AlipayApiClient.API_BASE), guardedUrls);
  }

  @Test
  @DisplayName("收件人为空或 null：本地即拒，不发请求")
  void rejectsBlankRecipient() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"code\":\"10000\"}");

    assertThrows(IllegalArgumentException.class, () -> client(stub).sendText("", "hi"));
    assertThrows(IllegalArgumentException.class, () -> client(stub).sendText(null, "hi"));

    assertEquals(0, stub.requestCount(), "收件人为空时不该打端点");
    assertEquals(0, guardedUrls.size());
  }

  @Test
  @DisplayName("业务码非 10000：抛错并带 code/sub_code/msg/sub_msg —— 这发生在 HTTP 200 下")
  void businessErrorCarriesTheWholeDiagnosis() {
    StubHttpClient stub =
        StubHttpClient.always(
            200,
            "{\"alipay_open_public_message_custom_send_response\":{"
                + "\"code\":\"40004\",\"msg\":\"Business Failed\","
                + "\"sub_code\":\"isv.invalid-parameter\",\"sub_msg\":\"参数无效\"}}");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).sendText("user-1", "hi"));

    String message = thrown.getMessage();
    assertTrue(message.contains("code=40004"), message);
    assertTrue(message.contains("sub_code=isv.invalid-parameter"), message);
    assertTrue(message.contains("Business Failed"), message);
    assertTrue(message.contains("参数无效"), message);
  }

  @Test
  @DisplayName("响应不带包裹时，裸 code 同样被识别")
  void handlesUnwrappedResponse() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"code\":\"40002\"}");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).sendText("user-1", "hi"));

    assertTrue(thrown.getMessage().contains("code=40002"), thrown.getMessage());
  }

  @Test
  @DisplayName("响应里没有 code：按成功处理（网关成功态不带该字段）")
  void treatsMissingCodeAsSuccess() {
    StubHttpClient stub =
        StubHttpClient.always(200, "{\"alipay_open_public_message_custom_send_response\":{}}");

    client(stub).sendText("user-1", "hi");

    assertEquals(1, stub.requestCount());
  }

  @Test
  @DisplayName("非 2xx 抛错并带状态码")
  void nonSuccessStatusCarriesItsCode() {
    StubHttpClient stub = StubHttpClient.always(502, "bad gateway");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client(stub).sendText("user-1", "hi"));

    assertTrue(thrown.getMessage().contains("HTTP 502"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("bad gateway"), thrown.getMessage());
  }

  @Test
  @DisplayName("text 为 null：biz_content 里 content 是空串，不是 null")
  void nullTextBecomesEmptyContent() {
    StubHttpClient stub = StubHttpClient.always(200, "{\"code\":\"10000\"}");

    client(stub).sendText("user-1", null);

    String biz = formValue(StubHttpClient.bodyOf(stub.requests().get(0)), "biz_content");
    assertTrue(biz.contains("\"content\":\"\""), biz);
    assertTrue(!biz.contains("null"), "不该把 JSON null 发给平台: " + biz);
  }
}
