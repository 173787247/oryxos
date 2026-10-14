package io.oryxos.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.StubHttpClient;
import io.oryxos.core.channel.StubWebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住 QQ Gateway 的协议处理：Hello → Identify、Dispatch 分发、断开的分类。
 *
 * <p>与 Discord 那份逐行同构，**差异正是这里要钉的**：
 *
 * <ul>
 *   <li>{@code token} 字段是 {@code authorizationHeader()}（形如 {@code "QQBot <token>"}），不是裸 token
 *   <li>Identify 多一个 {@code shard: [0, 1]}
 *   <li>{@code properties} 的键带 {@code $} 前缀（QQ 的格式，Discord 不带）
 *   <li>{@code intents} 是单个位（{@code 1 << 25}），不是 Discord 那种多标志位掩码
 * </ul>
 *
 * <p>连接建立没有可注入的接缝，但协议处理全在 {@code WebSocket.Listener} 回调里，喂一个 {@link StubWebSocket} 即可驱动。
 */
class QqGatewayClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BOT_TOKEN = "qq-bot-token-1";

  private final List<String> dispatched = new ArrayList<>();
  private final List<QqDisconnectKind> disconnects = new ArrayList<>();

  /** 换票客户端用打桩的 HttpClient 造：它的 accessToken() 会命中这个预置响应。 */
  private QqAccessTokenClient tokens() {
    StubHttpClient stub =
        StubHttpClient.always(
            200, "{\"code\":0,\"access_token\":\"" + BOT_TOKEN + "\",\"expires_in\":7200}");
    return new QqAccessTokenClient(stub, url -> {}, "app-id", "secret");
  }

  private QqGatewayClient client() {
    return new QqGatewayClient(
        tokens(), url -> {}, (type, payload) -> dispatched.add(type), disconnects::add);
  }

  private static void feed(QqGatewayClient client, StubWebSocket socket, String frame) {
    client.onText(socket, frame, true);
  }

  private static String hello(long heartbeatMs) {
    return "{\"op\":10,\"d\":{\"heartbeat_interval\":" + heartbeatMs + "}}";
  }

  @Test
  @DisplayName("HELLO：IDENTIFY 带 QQBot 前缀的 token、单个 intent、shard 与 $ 前缀的 properties")
  void helloTriggersIdentify() throws Exception {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, hello(600_000L));

    assertEquals(1, socket.sentText().size(), "HELLO 之后应当恰好发一帧");
    JsonNode identify = MAPPER.readTree(socket.lastText());
    JsonNode data = identify.path("d");
    assertEquals(2, identify.path("op").asInt(), "op=2 是 IDENTIFY");
    assertEquals(
        "QQBot " + BOT_TOKEN, data.path("token").asText(), "QQ 的 Identify 要鉴权头格式，塞裸 token 会被拒");
    assertEquals(QqGatewayClient.INTENTS, data.path("intents").asInt());
    assertEquals(2, data.path("shard").size(), "shard 必须存在且是两个元素");
    assertEquals(0, data.path("shard").get(0).asInt());
    assertTrue(data.path("properties").has("$browser"), socket.lastText());
  }

  @Test
  @DisplayName("Dispatch 帧：按事件名分发，payload 是 d")
  void dispatchReachesTheHandler() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(
        client,
        socket,
        "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"s\":7,\"d\":{\"content\":\"hi\"}}");

    assertEquals(List.of("C2C_MESSAGE_CREATE"), dispatched);
  }

  @Test
  @DisplayName("READY 是派发前的握手：不入业务分发")
  void readyIsNotDispatched() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":0,\"t\":\"READY\",\"s\":1,\"d\":{\"session_id\":\"s-1\"}}");

    assertTrue(dispatched.isEmpty(), "READY 不是业务事件，不该进分发: " + dispatched);
  }

  @Test
  @DisplayName("op=7 RECONNECT：优雅断开并通知一次")
  void reconnectClosesGracefully() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":7}");

    assertEquals(List.of(QqDisconnectKind.GRACEFUL), disconnects);
  }

  @Test
  @DisplayName("op=9 INVALID_SESSION：按非优雅断开通知")
  void invalidSessionIsAbrupt() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":9,\"d\":false}");

    assertEquals(List.of(QqDisconnectKind.ABRUPT), disconnects);
  }

  @Test
  @DisplayName("onClose：按非优雅断开通知")
  void closeIsAbrupt() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    client.onClose(socket, 1006, "abnormal");

    assertEquals(List.of(QqDisconnectKind.ABRUPT), disconnects);
  }

  @Test
  @DisplayName("断开只通知一次：重连风暴不该被放大成多次重连")
  void disconnectIsNotifiedOnce() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":7}");
    feed(client, socket, "{\"op\":7}");
    client.onClose(socket, 1006, "abnormal");

    assertEquals(1, disconnects.size(), "实际通知了 " + disconnects.size() + " 次");
  }

  @Test
  @DisplayName("坏 JSON：忽略该帧，不抛也不分发")
  void malformedFrameIsIgnored() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{not json");

    assertTrue(dispatched.isEmpty());
    assertTrue(socket.sentText().isEmpty(), "坏帧不该引出任何回包");
  }

  @Test
  @DisplayName("分片帧：last=false 时先累积，收到结尾再处理")
  void fragmentedFrameIsBufferedUntilLast() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    client.onText(socket, "{\"op\":0,\"t\":\"C2C_MESSAGE_", false);
    assertTrue(dispatched.isEmpty(), "片段未收完时不该处理");

    client.onText(socket, "CREATE\",\"d\":{}}", true);

    assertEquals(List.of("C2C_MESSAGE_CREATE"), dispatched);
  }

  @Test
  @DisplayName("每处理一帧都 request(1) —— 少这一步长连接会静默停收")
  void keepsRequestingFrames() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":11}");

    assertTrue(socket.requestedFrames() >= 1, "处理完一帧必须继续要下一帧");
  }

  @Test
  @DisplayName("op=1 服务端要求心跳：立即回一帧心跳")
  void serverRequestedHeartbeatIsAnswered() throws Exception {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    feed(client, socket, "{\"op\":1}");

    assertNotNull(socket.lastText(), "应当回一帧心跳");
    assertEquals(1, MAPPER.readTree(socket.lastText()).path("op").asInt());
  }

  @Test
  @DisplayName("onOpen 之后请求首帧（否则一帧都收不到）")
  void openRequestsTheFirstFrame() {
    QqGatewayClient client = client();
    StubWebSocket socket = new StubWebSocket();

    client.onOpen(socket);

    assertTrue(socket.requestedFrames() >= 1);
  }

  @Test
  @DisplayName("connect 前两个域都过闸：API 域与网关域")
  void guardCoversBothApiAndGatewayOrigins() {
    List<String> guarded = new ArrayList<>();
    // 第二次校验时抛：既钉住两个域都被校验，又让 connect 停在这里 —— 否则它会走到 fetchGatewayUrl()，
    // 那是个真 HTTP 请求、超时 20 秒，会把这条用例变成整个测试套件里最慢的一个。
    QqGatewayClient client =
        new QqGatewayClient(
            tokens(),
            url -> {
              guarded.add(url);
              if (guarded.size() == 2) {
                throw new IllegalStateException("stop before the network step");
              }
            },
            (type, payload) -> {},
            kind -> {});

    try {
      client.connect(Duration.ofMillis(200));
    } catch (Exception ignored) {
      // 期望走到第二次 guard 就停
    }

    assertEquals(2, guarded.size(), "两个域都该过闸，实际: " + guarded);
    assertTrue(guarded.contains(QqGatewayClient.API_BASE_URL), "API 域未过闸，实际: " + guarded);
    assertTrue(
        guarded.contains(QqGatewayClient.GATEWAY_ORIGIN_FOR_GUARD),
        "网关域名未过闸 —— 连接目标不校验，白名单就漏了主要的那一半，实际: " + guarded);
  }
}
