package io.oryxos.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QQ Bot {@code access_token}：{@code POST /app/getAppAccessToken}，过期前刷新，不落盘。
 *
 * <p>鉴权头格式：{@code Authorization: QQBot {access_token}}。
 */
final class QqAccessTokenClient {

  static final String API_BASE_URL = "https://api.bot.qq.com";
  static final String TOKEN_PATH = "/app/getAppAccessToken";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** 提前刷新余量，避免边界过期。 */
  private static final long REFRESH_SKEW_MS = 60_000L;

  private static final long DEFAULT_EXPIRES_MS = 7_200_000L;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_ACCESS_TOKEN = "access_token";
  private static final String FIELD_EXPIRES_IN = "expires_in";
  private static final String FIELD_CODE = "code";
  private static final String FIELD_MESSAGE = "message";

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String appId;
  private final String clientSecret;
  private final AtomicReference<CachedToken> cached = new AtomicReference<>();

  QqAccessTokenClient(OutboundGuard guard, String appId, String clientSecret) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, appId, clientSecret);
  }

  QqAccessTokenClient(HttpClient http, OutboundGuard guard, String appId, String clientSecret) {
    this.http = Objects.requireNonNull(http);
    this.guard = Objects.requireNonNull(guard);
    this.appId = Objects.requireNonNull(appId);
    this.clientSecret = Objects.requireNonNull(clientSecret);
  }

  String authorizationHeader() {
    return "QQBot " + accessToken();
  }

  String accessToken() {
    CachedToken current = cached.get();
    long now = System.currentTimeMillis();
    if (current != null && now < current.expiresAtMs() - REFRESH_SKEW_MS) {
      return current.token();
    }
    synchronized (this) {
      current = cached.get();
      now = System.currentTimeMillis();
      if (current != null && now < current.expiresAtMs() - REFRESH_SKEW_MS) {
        return current.token();
      }
      CachedToken refreshed = fetch();
      cached.set(refreshed);
      return refreshed.token();
    }
  }

  private CachedToken fetch() {
    String url = API_BASE_URL + TOKEN_PATH;
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("appId", appId);
      body.put("clientSecret", clientSecret);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      // 官方：业务错误走响应体的 code，失败时 HTTP 仍是 200；成功响应没有 code 字段。
      int code = root.path(FIELD_CODE).asInt(0);
      if (code != 0) {
        throw new IllegalStateException(businessFailure(code, root.path(FIELD_MESSAGE).asText("")));
      }
      String token = root.path(FIELD_ACCESS_TOKEN).asText("");
      if (token.isBlank()) {
        throw new IllegalStateException(
            "QQ getAppAccessToken 无 access_token: " + sanitize(response.body()));
      }
      long expiresInSec = parseExpiresIn(root.path(FIELD_EXPIRES_IN));
      long expiresAt = System.currentTimeMillis() + Math.max(1_000L, expiresInSec * 1_000L);
      return new CachedToken(token, expiresAt);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("QQ getAppAccessToken 失败: " + e.getMessage(), e);
    }
  }

  /**
   * 按官方文档的 {@code code} 归类业务错误。
   *
   * <p>官方写明 {@code message}「仅用于人工排查」且「不要依据 message 判定错误类型」，所以这里只把它原样附在 提示之后，判定一律看 {@code
   * code}；排查指南取官方「获取访问凭证」页的业务错误码表。
   */
  private static String businessFailure(int code, String message) {
    String hint =
        switch (code) {
          case 100001 -> "请求过于频繁，请降低调用频率后重试";
          case 100007 -> "AppID 无效，或机器人状态不正常（被封禁或已删除）";
          case 100016 -> "AppID 或 ClientSecret 不正确";
          case 10004 -> "AppID 对应的机器人不存在";
          default -> "官方文档未列出该错误码";
        };
    return "QQ getAppAccessToken 业务错误 code=" + code + "（" + hint + "）: " + sanitize(message);
  }

  private static long parseExpiresIn(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return DEFAULT_EXPIRES_MS / 1_000L;
    }
    if (node.isNumber()) {
      return node.asLong(DEFAULT_EXPIRES_MS / 1_000L);
    }
    String text = node.asText("");
    if (text.isBlank()) {
      return DEFAULT_EXPIRES_MS / 1_000L;
    }
    try {
      return Long.parseLong(text.strip());
    } catch (NumberFormatException e) {
      return DEFAULT_EXPIRES_MS / 1_000L;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  private record CachedToken(String token, long expiresAtMs) {}
}
