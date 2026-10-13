package io.oryxos.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.StubWebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住 Gateway 的协议处理：Hello → Identify、Dispatch 分发、以及断开的分类。
 *
 * <p>连接建立那段没有可注入的接缝（客户端在方法内部自建 {@code HttpClient}），但协议处理全在 {@code WebSocket.Listener} 的回调里，而回调接收一个
 * {@link java.net.http.WebSocket} 参数 —— 喂一个 {@link StubWebSocket} 就能驱动它，不必改产品代码。
 *
 * <p>不测心跳的定时行为（那要等真实时间），只测 Hello 之后**是否**发了 Identify、以及帧的解析与分发。
 */
class DiscordGatewayClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TOKEN = "bot-token-1";

  private final List<String> dispatched = new ArrayList<>();
  private final List<DiscordDisconnectKind> disconnects = new ArrayList<>();

  private DiscordGatewayClient client() {
    return new DiscordGatewayClient(
        TOKEN, url -> {}, (type, payload) -> dispatched.add(type), disconnects::add);
  }

  /** 心跳间隔给大值：这些用例不测心跳，也不想让线程在测试期间反复发帧。 */
  private static void feed(DiscordGatewayClient client, StubWebSocket socket, String frame) {
    client.onText(socket, frame, true);
  }

  private static String hello(long heartbeatMs) {
    return "{\"op\":10,\"d\":{\"heartbeat_interval\":" + heartbeatMs + "}}";
  }

  @Test
  @DisplayName("HELLO：回 IDENTIFY，带 token 与 intents")
  void helloTriggersIdentify() throws Exception {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, hello(600_000L));

    assertEquals(1, socket.sentText().size(), "HELLO 之后应当恰好发一帧");
    JsonNode identify = MAPPER.readTree(socket.lastText());
    assertEquals(2, identify.path("op").asInt(), "op=2 是 IDENTIFY");
    assertEquals(TOKEN, identify.path("d").path("token").asText());
    assertEquals(
        DiscordGatewayClient.INTENTS,
        identify.path("d").path("intents").asInt(),
        "intents 决定收不收得到消息，错一位就静默收不到");
    assertTrue(identify.path("d").path("properties").has("browser"), socket.lastText());
  }

  @Test
  @DisplayName("Dispatch 帧：按事件名分发，payload 是 d")
  void dispatchReachesTheHandler() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"s\":7,\"d\":{\"content\":\"hi\"}}");

    assertEquals(List.of("MESSAGE_CREATE"), dispatched);
  }

  @Test
  @DisplayName("READY 是派发前的握手：不入业务分发")
  void readyIsNotDispatched() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":0,\"t\":\"READY\",\"s\":1,\"d\":{\"session_id\":\"s-1\"}}");

    assertTrue(dispatched.isEmpty(), "READY 不是业务事件，不该进分发: " + dispatched);
  }

  @Test
  @DisplayName("op=7 RECONNECT：优雅断开并通知一次")
  void reconnectClosesGracefully() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":7}");

    assertEquals(List.of(DiscordDisconnectKind.GRACEFUL), disconnects);
  }

  @Test
  @DisplayName("op=9 INVALID_SESSION：按非优雅断开通知")
  void invalidSessionIsAbrupt() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":9,\"d\":false}");

    assertEquals(List.of(DiscordDisconnectKind.ABRUPT), disconnects);
  }

  @Test
  @DisplayName("onClose：按非优雅断开通知，并带上状态码")
  void closeIsAbrupt() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    client.onClose(socket, 1006, "abnormal");

    assertEquals(List.of(DiscordDisconnectKind.ABRUPT), disconnects);
  }

  @Test
  @DisplayName("断开只通知一次：重连风暴不该被放大成多次重连")
  void disconnectIsNotifiedOnce() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":7}");
    feed(client, socket, "{\"op\":7}");
    client.onClose(socket, 1006, "abnormal");

    assertEquals(1, disconnects.size(), "实际通知了 " + disconnects.size() + " 次");
  }

  @Test
  @DisplayName("坏 JSON：忽略该帧，不抛也不分发")
  void malformedFrameIsIgnored() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{not json");

    assertTrue(dispatched.isEmpty());
    assertTrue(socket.sentText().isEmpty(), "坏帧不该引出任何回包");
  }

  @Test
  @DisplayName("分片帧：last=false 时先累积，收到结尾再处理")
  void fragmentedFrameIsBufferedUntilLast() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    client.onText(socket, "{\"op\":0,\"t\":\"MESSAGE_", false);
    assertTrue(dispatched.isEmpty(), "片段未收完时不该处理");

    client.onText(socket, "CREATE\",\"d\":{}}", true);

    assertEquals(List.of("MESSAGE_CREATE"), dispatched);
  }

  @Test
  @DisplayName("每处理一帧都 request(1) —— 少这一步长连接会静默停收")
  void keepsRequestingFrames() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":11}");

    assertTrue(socket.requestedFrames() >= 1, "处理完一帧必须继续要下一帧");
  }

  @Test
  @DisplayName("op=1 服务端要求心跳：立即回一帧心跳")
  void serverRequestedHeartbeatIsAnswered() throws Exception {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":1}");

    assertNotNull(socket.lastText(), "应当回一帧心跳");
    JsonNode heartbeat = MAPPER.readTree(socket.lastText());
    assertEquals(1, heartbeat.path("op").asInt());
  }

  @Test
  @DisplayName("onOpen 之后请求首帧（否则一帧都收不到）")
  void openRequestsTheFirstFrame() {
    DiscordGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    client.onOpen(socket);

    assertTrue(socket.requestedFrames() >= 1);
  }

  @Test
  @DisplayName("connect 前两个域都过闸：API 域与网关域，缺一个都算漏检")
  void guardCoversBothApiAndGatewayOrigins() {
    List<String> guarded = new ArrayList<>();
    DiscordGatewayClient client =
        new DiscordGatewayClient(TOKEN, guarded::add, (type, payload) -> {}, kind -> {});

    try {
      client.connect(Duration.ofMillis(200));
    } catch (Exception ignored) {
      // 没有真网关，connect 必然失败；这里只关心它在连接前过了哪些闸
    }

    assertTrue(guarded.contains(DiscordGatewayClient.API_BASE_URL), "API 域未过闸，实际: " + guarded);
    assertTrue(
        guarded.contains(DiscordGatewayClient.GATEWAY_ORIGIN_FOR_GUARD),
        "网关域名未过闸 —— 连接目标不校验，白名单就漏了主要的那一半，实际: " + guarded);
  }
}
