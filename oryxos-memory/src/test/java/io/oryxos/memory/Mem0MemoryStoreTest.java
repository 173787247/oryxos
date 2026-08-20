package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Mem0 档专属（015 T021/T022）：mock 自托管 mem0 OSS 的 REST（JDK HttpServer 假服务），不碰真 server。 */
class Mem0MemoryStoreTest {

  private record Received(
      String path, String query, String body, String auth, String apiKeyHeader) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<Received> received = new ArrayList<>();
  private volatile int status = 200;
  private volatile String responseBody = "{\"results\":[]}";

  @BeforeEach
  void startFakeMem0() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(
              new Received(
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestURI().getQuery(),
                  body,
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("X-API-Key")));
          byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopFakeMem0() {
    server.stop(0);
    ToolExecutionContext.clear();
  }

  private Mem0MemoryStore store() {
    return store("");
  }

  private Mem0MemoryStore store(String apiKey) {
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    return new Mem0MemoryStore(RestClient.builder().baseUrl(baseUrl).build(), "agent-42", apiKey);
  }

  @Test
  @DisplayName("core 写入_infer:false 原文保真 + scope metadata（契约二）")
  void coreAppendIsVerbatimWithInferFalse() throws IOException {
    store().append("我叫小林，永远用中文回复", MemoryScope.CORE);

    Received req = received.get(0);
    assertEquals("/memories", req.path(), "OSS server 无 /v1 前缀");
    JsonNode body = MAPPER.readTree(req.body());
    assertEquals("我叫小林，永远用中文回复", body.get("messages").get(0).get("content").asText());
    assertEquals("CORE", body.get("metadata").get("scope").asText());
    assertFalse(body.get("infer").asBoolean(), "core 必须 infer:false 原文一字不差");
    assertEquals("agent-42", body.get("user_id").asText(), "无 Agent 上下文沿用基础 user-id");
  }

  @Test
  @DisplayName("archival 写入_infer:true 交 mem0 提炼")
  void archivalAppendUsesInferTrue() throws IOException {
    store().append("今天发布踩了灰度的坑", MemoryScope.ARCHIVAL);

    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertTrue(body.get("infer").asBoolean(), "archival 交 mem0 提炼/冲突消解");
    assertEquals("ARCHIVAL", body.get("metadata").get("scope").asText());
  }

  @Test
  @DisplayName("search 带 scope 过滤_返回侧再防御过滤核心条目（#3773 双保险）")
  void searchFiltersScopeOnBothSides() throws IOException {
    responseBody =
        "{\"results\":[{\"memory\":\"归档命中\",\"metadata\":{\"scope\":\"ARCHIVAL\"}},"
            + "{\"memory\":\"不该出现的核心\",\"metadata\":{\"scope\":\"CORE\"}},"
            + "{\"memory\":\"无标记的提炼结果\"}]}";

    List<String> hits = store().recallByKeyword("命中");

    assertEquals("/search", received.get(0).path());
    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertEquals("ARCHIVAL", body.get("filters").get("scope").asText(), "请求侧 filters");
    assertEquals(List.of("归档命中", "无标记的提炼结果"), hits, "CORE 标记条目被返回侧过滤掉");
  }

  @Test
  @DisplayName("load_get_all 一次取回按 metadata 分区_core 全量 archival 窗口")
  void loadPartitionsByMetadataScope() {
    responseBody =
        "{\"results\":[{\"memory\":\"核心事实\",\"metadata\":{\"scope\":\"CORE\"}},"
            + "{\"memory\":\"归档事件\",\"metadata\":{\"scope\":\"ARCHIVAL\"}}]}";

    String loaded = store().load();

    assertEquals("/memories", received.get(0).path());
    assertTrue(received.get(0).query().contains("user_id=agent-42"));
    assertTrue(loaded.indexOf("核心事实") < loaded.indexOf("## 归档记忆"), "核心区在前区块");
    assertTrue(loaded.indexOf("归档事件") > loaded.indexOf("## 归档记忆"), "归档区在后区块");
  }

  @Test
  @DisplayName("配置 api-key_同时带 Bearer 与 X-API-Key；留空不发鉴权头")
  void apiKeySendsBothAuthHeadersOnlyWhenConfigured() {
    store("sk-mem0-test").append("x", MemoryScope.ARCHIVAL);
    assertEquals("Bearer sk-mem0-test", received.get(0).auth());
    assertEquals("sk-mem0-test", received.get(0).apiKeyHeader());

    store().append("y", MemoryScope.ARCHIVAL);
    assertTrue(received.get(1).auth() == null, "留空（OSS 免鉴权部署）不发 Authorization");
    assertTrue(received.get(1).apiKeyHeader() == null, "也不发 X-API-Key");
  }

  @Test
  @DisplayName("有 Agent 上下文_user_id 追加 agent 段（per-Agent 隔离）")
  void agentContextScopesUserId() throws IOException {
    ToolExecutionContext.setAgentName("ops-agent");

    store().append("A 的记忆", MemoryScope.ARCHIVAL);

    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertEquals("agent-42:ops-agent", body.get("user_id").asText());
  }

  @Test
  @DisplayName("连接异常_转可读 IllegalStateException（故障口径统一）")
  void connectionFailureBecomesReadableException() {
    server.stop(0); // 端口失效 → 连接被拒

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> store().recallByKeyword("x"));

    assertTrue(ex.getMessage().contains("mem0"), "报错点名 mem0");
    assertTrue(ex.getMessage().contains("base-url"), "给出可行动的排查提示");
  }

  @Test
  @DisplayName("5xx_同样转可读 IllegalStateException 不裸抛 REST 异常")
  void serverErrorBecomesReadableException() {
    status = 500;

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> store().append("x", MemoryScope.ARCHIVAL));
    assertTrue(ex.getMessage().contains("写入"), "口径点名动作");
  }

  @Test
  @DisplayName("capabilities=DELEGATED_archivalEntries 为空")
  void delegatedCapabilityShape() {
    assertEquals(MemoryRecallCapability.DELEGATED, store().capabilities());
    assertTrue(store().archivalEntries().isEmpty());
  }
}
