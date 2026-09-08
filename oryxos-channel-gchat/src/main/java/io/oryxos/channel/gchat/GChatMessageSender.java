package io.oryxos.channel.gchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Chat API {@code POST /v1/{space}/messages}。 */
public class GChatMessageSender {

  static final String API_BASE = "https://chat.googleapis.com/v1/";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String accessToken;

  public GChatMessageSender(OutboundGuard guard, String accessToken) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, accessToken);
  }

  GChatMessageSender(HttpClient http, OutboundGuard guard, String accessToken) {
    this.http = http;
    this.guard = guard;
    this.accessToken = accessToken;
  }

  public void send(String spaceName, String text, String replyToMessageId) {
    String url = API_BASE + spaceName + "/messages";
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("text", text == null ? "" : text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.putObject("thread").put("name", replyToMessageId);
      }
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + accessToken)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Google Chat 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Google Chat 发消息失败: " + e.getMessage(), e);
    }
  }
}
