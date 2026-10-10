package io.oryxos.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatrixMessageSenderTest {

  /**
   * Matrix 规范对事件总大小的规定是 {@code MUST NOT exceed 65 KB} （matrix-spec-proposals#1021 在争确切字节数，数量级确定）。
   *
   * <p>这里写成字节数并除以 3 —— 中文在 UTF-8 下一个字符占 3 字节， 而 {@code OutboundTextSegments.split} 按字符切。
   */
  private static final int PLATFORM_CAP_BYTES = 65 * 1024;

  private static final int PLATFORM_CAP_CHARS_FOR_CJK = PLATFORM_CAP_BYTES / 3;

  private HttpServer server;
  private final List<String> paths = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/_matrix/client/v3/rooms/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respond(exchange, 200, "{\"event_id\":\"$1\"}");
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private MatrixMessageSender sender() {
    return new MatrixMessageSender(
        HttpClient.newHttpClient(),
        url -> {},
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "tok");
  }

  @Test
  @DisplayName("默认分段上限在按中文 3 字节折算后仍不超事件上限")
  void defaultChunkSizeFitsUnderThePlatformCap() {
    assertTrue(MatrixMessageSender.DEFAULT_CHUNK_SIZE > 0);
    assertTrue(
        MatrixMessageSender.DEFAULT_CHUNK_SIZE <= PLATFORM_CAP_CHARS_FOR_CJK,
        "默认分段 "
            + MatrixMessageSender.DEFAULT_CHUNK_SIZE
            + " 字符；全中文时 "
            + (MatrixMessageSender.DEFAULT_CHUNK_SIZE * 3)
            + " 字节，超过事件上限 "
            + PLATFORM_CAP_BYTES
            + " 字节");
  }

  @Test
  @DisplayName("超长回复被切成多段，每段不超上限，拼接后一字不差")
  void longReplyIsSplitAndReassembles() {
    String reply = "字".repeat(MatrixMessageSender.DEFAULT_CHUNK_SIZE * 2 + 137);
    List<String> parts = MatrixMessageSender.segment(reply, MatrixMessageSender.DEFAULT_CHUNK_SIZE);
    assertTrue(parts.size() >= 3, "应切成多段，实际 " + parts.size());
    for (String part : parts) {
      assertTrue(
          part.length() <= MatrixMessageSender.DEFAULT_CHUNK_SIZE, "有段超上限: " + part.length());
    }
    assertEquals(reply, String.join("", parts), "分段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只切一段，不额外分包")
  void shortReplyStaysWhole() {
    assertEquals(
        List.of("你好"), MatrixMessageSender.segment("你好", MatrixMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("空文本仍产出一段（保持「必有回复」语义）")
  void emptyYieldsOneEmptyPart() {
    assertEquals(
        List.of(""), MatrixMessageSender.segment("", MatrixMessageSender.DEFAULT_CHUNK_SIZE));
    assertEquals(
        List.of(""), MatrixMessageSender.segment(null, MatrixMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("超长回复逐段发送：请求数等于段数，各段拼接与原回复一致")
  void longReplyPostsOncePerChunk() {
    String reply = "字".repeat(MatrixMessageSender.DEFAULT_CHUNK_SIZE * 2 + 11);
    sender().send("!room:example.org", reply);
    assertEquals(3, bodies.size(), "应发 3 次，实际 " + bodies.size());
    StringBuilder joined = new StringBuilder();
    for (String body : bodies) {
      int i = body.indexOf("\"body\":\"");
      assertTrue(i >= 0, "请求体里应有 body 字段: " + body);
      int j = body.indexOf('"', i + 8);
      joined.append(body, i + 8, j);
    }
    assertEquals(reply, joined.toString(), "各段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只发一次请求")
  void shortReplyPostsOnce() {
    sender().send("!room:example.org", "你好");
    assertEquals(1, bodies.size(), "应发 1 次，实际 " + bodies.size());
  }

  @Test
  @DisplayName("每段用不同的事务 id —— 相同 txnId 会被 homeserver 当成重发丢掉")
  void everyChunkGetsItsOwnTransactionId() {
    sender().send("!room:example.org", "字".repeat(MatrixMessageSender.DEFAULT_CHUNK_SIZE * 2 + 11));
    assertEquals(3, paths.size(), "应发 3 次");
    long distinct = paths.stream().map(p -> p.substring(p.lastIndexOf('/') + 1)).distinct().count();
    assertEquals(3, distinct, "3 段的 txnId 必须互不相同，实际 " + paths);
  }
}
