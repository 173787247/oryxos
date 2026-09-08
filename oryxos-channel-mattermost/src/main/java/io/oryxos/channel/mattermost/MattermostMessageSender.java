package io.oryxos.channel.mattermost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Mattermost {@code POST /api/v4/posts}。 */
public class MattermostMessageSender {

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String baseUrl;
  private final String token;

  public MattermostMessageSender(OutboundGuard guard, String baseUrl, String token) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, baseUrl, token);
  }

  MattermostMessageSender(HttpClient http, OutboundGuard guard, String baseUrl, String token) {
    this.http = http;
    this.guard = guard;
    this.baseUrl = trimSlash(baseUrl);
    this.token = token;
  }

  public void send(String channelId, String text, String replyToMessageId) {
    String url = baseUrl + "/api/v4/posts";
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("channel_id", channelId);
      body.put("message", text == null ? "" : text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.put("root_id", replyToMessageId);
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
        throw new IllegalStateException("Mattermost 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Mattermost 发消息失败: " + e.getMessage(), e);
    }
  }

  static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
