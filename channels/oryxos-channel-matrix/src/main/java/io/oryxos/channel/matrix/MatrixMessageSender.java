package io.oryxos.channel.matrix;

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
import java.util.UUID;

/** Matrix {@code PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}}。 */
public class MatrixMessageSender {

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String MSGTYPE_TEXT = "m.text";

  /**
   * 单条消息正文上限（字符数），留出与同族渠道一致的余量。
   *
   * <p>Matrix 规范对【事件总大小】的规定是：{@code The total size of any event MUST NOT exceed 65
   * KB}（matrix-spec-proposals#1021 在讨论它的确切字节数，建议定为 {@code <= 65507} bytes，数量级确定）。那是整个事件的大小，含 JSON
   * 结构与事件字段，不只是 {@code body}。
   *
   * <p>★ 这里要注意计数单位：{@link io.oryxos.core.channel.OutboundTextSegments#split} 按 <b>字符</b>切，而 65 KB
   * 是<b>字节</b>。中文在 UTF-8 下一个字符占 3 字节，所以 「65000 字符」在全中文时会变成约 190 KB —— 超限三倍。取 20000 字符， 全中文时约 60
   * KB，加上 JSON 外壳与事件字段仍在上限内。
   */
  static final int DEFAULT_CHUNK_SIZE = 20000;

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String homeserver;
  private final String token;

  public MatrixMessageSender(OutboundGuard guard, String homeserver, String token) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, homeserver, token);
  }

  MatrixMessageSender(HttpClient http, OutboundGuard guard, String homeserver, String token) {
    this.http = http;
    this.guard = guard;
    this.homeserver = trimSlash(homeserver);
    this.token = token;
  }

  /**
   * 逐段发送。超过事件上限时整个请求会被 homeserver 拒掉（M413）， 那条回复就没了 —— 分段是既有的约定，见 {@link
   * io.oryxos.core.channel.OutboundTextSegments}。
   */
  public void send(String roomId, String text) {
    for (String chunk : segment(text == null ? "" : text, DEFAULT_CHUNK_SIZE)) {
      postMessage(roomId, chunk);
    }
  }

  private void postMessage(String roomId, String text) {
    // ★ 事务 id 是 Matrix 的去重键，必须【每段一个】。若提到循环外只生成一次，
    //   多段会共用同一个 txnId，homeserver 会把后续几段当成重发而丢掉。
    String txn = UUID.randomUUID().toString();
    String url =
        homeserver
            + "/_matrix/client/v3/rooms/"
            + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
            + "/send/m.room.message/"
            + txn;
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("msgtype", MSGTYPE_TEXT);
      body.put("body", text == null ? "" : text);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json; charset=utf-8")
              .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException("Matrix 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Matrix 发消息失败: " + e.getMessage(), e);
    }
  }

  static List<String> segment(String text, int chunkSize) {
    return io.oryxos.core.channel.OutboundTextSegments.split(text, chunkSize);
  }

  static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
