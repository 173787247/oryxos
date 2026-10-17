package io.oryxos.channel.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DingTalkStreamClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("机器人回调帧触发 onBotMessage 并 ACK")
  void botCallbackDispatchesMessageAndAcks() throws Exception {
    List<JsonNode> messages = new ArrayList<>();
    AtomicReference<String> ack = new AtomicReference<>();
    WebSocket ws = mockWebSocket(ack);
    DingTalkStreamClient client =
        new DingTalkStreamClient("id", "secret", url -> {}, messages::add, k -> {});
    String frame =
        """
        {
          "type": "CALLBACK",
          "headers": {
            "topic": "/v1.0/im/bot/messages/get",
            "messageId": "mid-1"
          },
          "data": "{\\"msgId\\":\\"m1\\",\\"conversationType\\":\\"1\\",\\"conversationId\\":\\"c1\\",\\"senderId\\":\\"u1\\",\\"msgtype\\":\\"text\\",\\"text\\":{\\"content\\":\\"hi\\"}}"
        }
        """;
    client.dispatchFrameForTest(frame, ws);

    assertEquals(1, messages.size());
    assertEquals("m1", messages.get(0).path("msgId").asText());
    JsonNode ackJson = MAPPER.readTree(ack.get());
    assertEquals(200, ackJson.path("code").asInt());
    assertEquals("mid-1", ackJson.path("headers").path("messageId").asText());
  }

  @Test
  @DisplayName("ping 帧回 opaque ACK")
  void pingReturnsOpaqueAck() throws Exception {
    AtomicReference<String> ack = new AtomicReference<>();
    WebSocket ws = mockWebSocket(ack);
    DingTalkStreamClient client =
        new DingTalkStreamClient("id", "secret", url -> {}, node -> {}, k -> {});
    String frame =
        """
        {
          "type": "SYSTEM",
          "headers": {
            "topic": "ping",
            "messageId": "ping-1"
          },
          "data": "{\\"opaque\\":\\"abc-123\\"}"
        }
        """;
    client.dispatchFrameForTest(frame, ws);

    JsonNode ackJson = MAPPER.readTree(ack.get());
    assertEquals("abc-123", MAPPER.readTree(ackJson.path("data").asText()).path("opaque").asText());
  }

  @Test
  @DisplayName("disconnect 帧主动 closeQuietly 并通知上层")
  void disconnectTopicClosesSocketAndNotifies() throws Exception {
    AtomicReference<DingTalkDisconnectKind> kind = new AtomicReference<>();
    WebSocket ws = mock(WebSocket.class);
    when(ws.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(ws));
    DingTalkStreamClient client =
        new DingTalkStreamClient("id", "secret", url -> {}, node -> {}, kind::set);
    seedOpenClient(client, ws);

    String frame =
        """
        {
          "type": "SYSTEM",
          "headers": {
            "topic": "disconnect",
            "messageId": "disc-1"
          },
          "data": "{\\"reason\\":\\"server maintenance\\"}"
        }
        """;
    client.dispatchFrameForTest(frame, ws);

    assertFalse(client.isConnected());
    assertEquals(DingTalkDisconnectKind.GRACEFUL, kind.get());
    verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "bye");
  }

  @Test
  @Timeout(20)
  @DisplayName("对端不回应 FIN_closeQuietly 仍在约 2 秒内返回")
  void closeQuietlyBoundedWhenCloseNeverCompletes() throws Exception {
    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch neverFinishes = new CountDownLatch(1);
    DingTalkStreamClient client =
        new DingTalkStreamClient("id", "secret", url -> {}, node -> {}, kind -> {}) {
          @Override
          void closeWebSocket(WebSocket ws) {
            closeEntered.countDown();
            try {
              neverFinishes.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    seedOpenClient(client, mock(WebSocket.class));

    long startedAt = System.nanoTime();
    try {
      client.closeQuietly();
    } finally {
      neverFinishes.countDown(); // 放掉守护线程，别把测试自己的限时线程留在 JVM 里
    }
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(closeEntered.await(1, TimeUnit.SECONDS), "close 必须真的被尝试过（不是 socket 为空直接返回）");
    assertTrue(elapsedMs >= 1_500, "应等满 2s 限时才返回，实际 " + elapsedMs + "ms");
    assertTrue(elapsedMs <= 4_000, "不得无限等待，实际 " + elapsedMs + "ms");
    assertFalse(client.isConnected(), "放弃等待后仍须置为断开");
  }

  @Test
  @Timeout(20)
  @DisplayName("正常关闭路径：对端回 FIN 后立即返回，不等满限时")
  void closeQuietlyFastWhenPeerAcksClose() throws Exception {
    WebSocket ws = mock(WebSocket.class);
    when(ws.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(ws));
    DingTalkStreamClient client =
        new DingTalkStreamClient("id", "secret", url -> {}, node -> {}, kind -> {});
    seedOpenClient(client, ws);

    long startedAt = System.nanoTime();
    client.closeQuietly();
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    assertTrue(elapsedMs < 1_500, "回 FIN 时不应等满限时，实际 " + elapsedMs + "ms");
  }

  private static void seedOpenClient(DingTalkStreamClient client, WebSocket ws) throws Exception {
    var socketField = DingTalkStreamClient.class.getDeclaredField("socket");
    socketField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<WebSocket> socketRef = (AtomicReference<WebSocket>) socketField.get(client);
    socketRef.set(ws);
    var connectedField = DingTalkStreamClient.class.getDeclaredField("connected");
    connectedField.setAccessible(true);
    ((AtomicBoolean) connectedField.get(client)).set(true);
    var closedField = DingTalkStreamClient.class.getDeclaredField("closed");
    closedField.setAccessible(true);
    ((AtomicBoolean) closedField.get(client)).set(false);
  }

  private static WebSocket mockWebSocket(AtomicReference<String> ack) {
    WebSocket ws = mock(WebSocket.class);
    when(ws.sendText(anyString(), anyBoolean()))
        .thenAnswer(
            inv -> {
              ack.set(inv.getArgument(0));
              return CompletableFuture.completedFuture(ws);
            });
    return ws;
  }
}
