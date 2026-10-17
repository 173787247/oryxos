package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

class WeComWsClientTest {

  @Test
  @DisplayName("onError 后清除订阅态，status 不应再报 CONNECTED")
  void onErrorClearsSubscriptionState() throws Exception {
    AtomicBoolean disconnected = new AtomicBoolean(false);
    WeComWsClient client =
        new WeComWsClient(
            "bot",
            "secret",
            WeComWsClient.DEFAULT_WS_URL,
            node -> {},
            () -> disconnected.set(true));
    var subscribed = WeComWsClient.class.getDeclaredField("subscribed");
    subscribed.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) subscribed.get(client)).set(true);
    var closed = WeComWsClient.class.getDeclaredField("closed");
    closed.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) closed.get(client)).set(false);

    client.onError(null, new RuntimeException("connection reset"));

    assertFalse(client.isSubscribed());
    assertTrue(disconnected.get());
  }

  @Test
  @DisplayName("无 cmd + errcode≠0 的回执帧_WARN 落日志不静默")
  void errorReceiptFrameIsLoggedNotSwallowed() {
    Logger logger = (Logger) LoggerFactory.getLogger(WeComWsClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      WeComWsClient client =
          new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {});
      WebSocket ws = mock(WebSocket.class);
      client.onText(ws, "{\"errcode\":0}", true);
      assertTrue(client.isSubscribed());

      client.onText(ws, "{\"errcode\":14,\"errmsg\":\"bad req_id\"}", true);

      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getLevel() == Level.WARN
                          && e.getFormattedMessage().contains("errcode=14")
                          && e.getFormattedMessage().contains("bad req_id")),
          "平台错误回执必须 WARN 落日志");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName("无 cmd + errcode=0 的控制帧_仍静默忽略")
  void okControlFrameStaysSilent() {
    Logger logger = (Logger) LoggerFactory.getLogger(WeComWsClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      WeComWsClient client =
          new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {});
      WebSocket ws = mock(WebSocket.class);
      client.onText(ws, "{\"errcode\":0}", true);
      int afterSubscribe = appender.list.size();

      client.onText(ws, "{\"errcode\":0}", true);

      assertTrue(appender.list.size() == afterSubscribe, "errcode=0 控制帧不得产生日志");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @Timeout(20)
  @DisplayName("对端不回应 FIN_closeQuietly 仍在约 2 秒内返回，且 HttpClient 照常释放")
  void closeQuietlyBoundedWhenCloseNeverCompletes_httpClientStillReleased() throws Exception {
    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch neverFinishes = new CountDownLatch(1);
    WeComWsClient client =
        new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {}) {
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
    HttpClient httpClient = mock(HttpClient.class);
    seedSocket(client, mock(WebSocket.class));
    seedHttpClient(client, httpClient);

    long startedAt = System.nanoTime();
    try {
      client.closeQuietly();
    } finally {
      neverFinishes.countDown(); // 放掉守护线程，别把测试自己的限时线程留在 JVM 里
    }
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(closeEntered.await(1, TimeUnit.SECONDS), "close 必须真的被尝试过");
    assertTrue(elapsedMs >= 1_500, "应等满 2s 限时才返回，实际 " + elapsedMs + "ms");
    assertTrue(elapsedMs <= 4_000, "不得无限等待，实际 " + elapsedMs + "ms");
    verify(httpClient).shutdownNow();
    assertFalse(client.isSubscribed(), "放弃等待后仍须置为未订阅");
  }

  @Test
  @Timeout(20)
  @DisplayName("正常关闭路径：对端回 FIN 后立即返回并释放 HttpClient")
  void closeQuietlyFastWhenPeerAcksClose() throws Exception {
    WebSocket ws = mock(WebSocket.class);
    when(ws.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(ws));
    HttpClient httpClient = mock(HttpClient.class);
    WeComWsClient client =
        new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {});
    seedSocket(client, ws);
    seedHttpClient(client, httpClient);

    long startedAt = System.nanoTime();
    client.closeQuietly();
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    verify(httpClient).shutdownNow();
    assertTrue(elapsedMs < 1_500, "回 FIN 时不应等满限时，实际 " + elapsedMs + "ms");
  }

  private static void seedSocket(WeComWsClient client, WebSocket ws) throws Exception {
    var socketField = WeComWsClient.class.getDeclaredField("socket");
    socketField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<WebSocket> socketRef = (AtomicReference<WebSocket>) socketField.get(client);
    socketRef.set(ws);
  }

  private static void seedHttpClient(WeComWsClient client, HttpClient httpClient) throws Exception {
    var httpClientField = WeComWsClient.class.getDeclaredField("httpClient");
    httpClientField.setAccessible(true);
    httpClientField.set(client, httpClient);
  }
}
