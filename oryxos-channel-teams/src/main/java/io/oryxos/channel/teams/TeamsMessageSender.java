package io.oryxos.channel.teams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Bot Framework 回复：client_credentials 换 token 后 POST {@code /v3/conversations/{id}/activities}。 */
public class TeamsMessageSender {

  static final String LOGIN_HOST = "https://login.microsoftonline.com";
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_ACCESS_TOKEN = "access_token";

  /**
   * 单条消息正文上限（字符数），留出与同族渠道一致的余量。
   *
   * <p>Teams 官方文档（learn.microsoft.com，Format agent messages → Message size limits）： {@code The
   * agent message size limit is 100 KB … ensure that the size of the message itself is within 80 KB
   * to guarantee successful message delivery}，超限时返回 {@code 413} （{@code RequestEntityTooLarge}，错误码
   * {@code MessageSizeTooBig}）。
   *
   * <p>★ 官方明说那个 KB 是 {@code encoded as UTF-16} 且包含 text、@-mentions 与 reactions。 UTF-16 下中文属
   * BMP，一个字符 2 字节 ⇒ 80 KB 约合 40960 个中文字符。 这里取 30000，给 @-mentions 与 JSON 外壳留出余量。
   */
  static final int DEFAULT_CHUNK_SIZE = 30000;

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String appId;
  private final String appSecret;
  private final String tenantId;

  public TeamsMessageSender(OutboundGuard guard, String appId, String appSecret, String tenantId) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, appId, appSecret, tenantId);
  }

  TeamsMessageSender(
      HttpClient http, OutboundGuard guard, String appId, String appSecret, String tenantId) {
    this.http = http;
    this.guard = guard;
    this.appId = appId;
    this.appSecret = appSecret;
    this.tenantId = tenantId;
  }

  /**
   * 逐段发送。超过单条上限时整个请求被拒（413），那条回复就没了 —— 分段是既有的约定，见 {@link
   * io.oryxos.core.channel.OutboundTextSegments}。
   */
  public void send(String serviceUrl, String conversationId, String text, String replyToMessageId) {
    // ★ token 与 url 都提到循环外。
    //   fetchToken() 本身是一次 HTTP（client_credentials），放进循环会让三段回复打三次登录接口；
    //   guard.check(url) 同理，没必要重复判。
    String token = fetchToken();
    String url =
        trimSlash(serviceUrl) + "/v3/conversations/" + urlEncode(conversationId) + "/activities";
    guard.check(url);
    for (String chunk : segment(text == null ? "" : text, DEFAULT_CHUNK_SIZE)) {
      postMessage(url, token, chunk, replyToMessageId);
    }
  }

  private void postMessage(String url, String token, String text, String replyToMessageId) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("type", "message");
      body.put("text", text == null ? "" : text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.put("replyToId", replyToMessageId);
      }
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException("Teams 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Teams 发消息失败: " + e.getMessage(), e);
    }
  }

  private String fetchToken() {
    String url = LOGIN_HOST + "/" + tenantId + "/oauth2/v2.0/token";
    guard.check(url);
    String form =
        "grant_type=client_credentials&client_id="
            + urlEncode(appId)
            + "&client_secret="
            + urlEncode(appSecret)
            + "&scope="
            + urlEncode("https://api.botframework.com/.default");
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      String token = root.path(FIELD_ACCESS_TOKEN).asText("");
      if (token.isBlank()) {
        throw new IllegalStateException("Teams 换 token 失败");
      }
      return token;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Teams 换 token 失败: " + e.getMessage(), e);
    }
  }

  static List<String> segment(String text, int chunkSize) {
    return io.oryxos.core.channel.OutboundTextSegments.split(text, chunkSize);
  }

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
