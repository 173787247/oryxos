package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.a2a.A2aAgentCardService;
import io.oryxos.core.a2a.A2aMessageService;
import io.oryxos.core.a2a.A2aProperties;
import io.oryxos.core.a2a.A2aRemoteClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A2A 开关打开态端到端（无 key、无网络，gate 内可跑）：真的把 {@code oryxos.a2a.enabled=true} 启动一次，验证——
 *
 * <ul>
 *   <li>context 正常启动，四个条件 Bean（卡片服务 / 消息服务 / 远端客户端 / 任一控制器）都装上；
 *   <li>{@code GET /.well-known/agent-card.json} 公开可达，返回裸 JSON 卡片且路径/能力符合 A2A 语义；
 *   <li>{@code POST /api/v1/a2a} 未认证得 401（filter 装配），带令牌打到控制器得 JSON-RPC 错误（端点映射）；
 *   <li>{@code message/send} 完整往返：真实 ReAct（mock provider）跑完，200 + 合法 JSON-RPC 2.0 信封 + 非空 result；
 *   <li>{@code X-A2A-Hop} 超限得 403（跳数 filter 装配）。
 * </ul>
 *
 * <p>shared-token 刻意配成非空：空的 shared-token 按设计是「A2A 无专属认证、端点公开」，那样测不出鉴权链。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oryxos.providers[0].name=mock",
      "oryxos.a2a.enabled=true",
      "oryxos.a2a.shared-token=" + A2aStartupE2ETest.TOKEN,
      "oryxos.a2a.public-base-url=http://a2a.example:8077"
    })
class A2aStartupE2ETest {

  static final String TOKEN = "a2a-e2e-shared-token";
  private static final String AGENT = "a2a-agent";
  private static final String CARD_PATH = "/.well-known/agent-card.json";
  private static final String RPC_PATH = "/api/v1/a2a";
  private static final Path ROOT = seedWorkspace();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private ApplicationContext context;
  @Autowired private A2aProperties a2aProperties;
  @Autowired private A2aAgentCardService cardService;
  @Autowired private A2aMessageService messageService;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-a2a-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve(AGENT));
      Files.writeString(
          root.resolve("agents/" + AGENT + "/AGENT.md"),
          """
          ---
          name: a2a-agent
          description: A2A 往返自测 Agent
          identity:
            agent_name: a2a小欧
            prompt: 你是一个 A2A 测试助手。
          provider:
            name: mock
            model: mock-model
          tools:
            - save_memory
            - recall_memory
          settings:
            max_iterations: 10
            max_history_turns: 20
          ---
          你是一个 A2A 测试助手，被调用时正常回应。
          """);
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) {
    registry.add("oryxos.root", ROOT::toString);
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("a2a-e2e.db"));
  }

  @Test
  @DisplayName("开关打开_context起得来_A2A条件组件全部装配")
  void switchOn_contextStartsWithAllA2aComponents() {
    assertTrue(a2aProperties.enabled(), "oryxos.a2a.enabled 应绑定为 true");
    assertNotNull(cardService, "A2aAgentCardService 应存在");
    assertNotNull(messageService, "A2aMessageService 应存在");
    assertNotNull(context.getBean(A2aRemoteClient.class), "A2aRemoteClient 应存在");
    assertNotNull(
        context.getBean(io.oryxos.web.controller.A2aAgentCardController.class), "卡片控制器应存在");
    assertNotNull(
        context.getBean(io.oryxos.web.controller.A2aJsonRpcController.class), "JSON-RPC 控制器应存在");
  }

  @Test
  @DisplayName("卡片端点公开可达_字段形状符合A2A卡片语义")
  void agentCard_isPublicAndWellFormed() throws Exception {
    ResponseEntity<String> response = rest.getForEntity(CARD_PATH, String.class);

    assertEquals(200, response.getStatusCode().value(), "开关打开时卡片端点应 200");
    MediaType contentType = response.getHeaders().getContentType();
    assertNotNull(contentType, "卡片应带 Content-Type");
    assertTrue(
        MediaType.APPLICATION_JSON.includes(contentType), "卡片应是 application/json：" + contentType);

    JsonNode card = mapper.readTree(response.getBody());
    assertEquals("0.3.0", card.get("protocolVersion").asText(), "A2A 协议版本");
    assertEquals("OryxOS", card.get("name").asText(), "卡片名称取 oryxos.a2a.name 默认值");
    assertEquals(
        "http://a2a.example:8077/api/v1/a2a",
        card.get("url").asText(),
        "卡片 url 应为 public-base-url + /api/v1/a2a");
    assertEquals("JSONRPC", card.get("preferredTransport").asText(), "默认传输 JSONRPC");
    assertTrue(card.get("capabilities").get("streaming").asBoolean(), "应声明 message/stream 流式能力");
    assertTrue(card.get("skills").isArray(), "skills 应为数组");
    assertTrue(
        StreamSupport.stream(card.get("skills").spliterator(), false)
            .allMatch(skill -> skill.hasNonNull("id") && skill.hasNonNull("name")),
        "每项 skill 都应带 id/name");
    assertEquals("text/plain", card.get("defaultInputModes").get(0).asText(), "默认输入模态");
    assertEquals("text/plain", card.get("defaultOutputModes").get(0).asText(), "默认输出模态");
  }

  @Test
  @DisplayName("JSONRPC未认证_401而非404_filter在端点前拦下")
  void jsonRpc_withoutCredentials_is401() {
    ResponseEntity<String> response = postJson("{}", null, null);

    assertEquals(401, response.getStatusCode().value(), "未带共享令牌应 401（说明 filter 已装配）");
    assertEquals(
        "Bearer realm=\"OryxOS-A2A\"",
        response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE),
        "应返回 A2A 专属的认证挑战");
    assertTrue(
        response.getBody() != null && response.getBody().contains("Unauthorized"),
        "401 响应体应说明未认证：" + response.getBody());
  }

  @Test
  @DisplayName("JSONRPC错令牌_401")
  void jsonRpc_withWrongToken_is401() {
    ResponseEntity<String> response = postJson("{}", "not-the-shared-token", null);

    assertEquals(401, response.getStatusCode().value(), "错令牌应 401");
  }

  @Test
  @DisplayName("JSONRPC带令牌_控制器已映射_返回JSONRPC错误而非404")
  void jsonRpc_withToken_reachesController() throws Exception {
    ResponseEntity<String> response = postJson("{}", TOKEN, null);

    assertEquals(200, response.getStatusCode().value(), "带令牌应进入控制器（HTTP 200 + JSON-RPC 错误）");
    JsonNode body = mapper.readTree(response.getBody());
    assertEquals("2.0", body.get("jsonrpc").asText(), "JSON-RPC 2.0 信封");
    assertTrue(body.get("id").isNull(), "空请求的 id 为 null");
    assertEquals(-32600, body.get("error").get("code").asInt(), "非法请求应返回 INVALID_REQUEST");
  }

  @Test
  @DisplayName("JSONRPC message/send 完整往返_200且合法JSON-RPC信封_result非空")
  void jsonRpc_messageSend_roundTrip() throws Exception {
    String request =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\",\"params\":{"
            + "\"message\":{\"kind\":\"message\",\"role\":\"user\",\"messageId\":\"m-1\","
            + "\"parts\":[{\"kind\":\"text\",\"text\":\"记录一次 A2A 往返\"}]},"
            + "\"metadata\":{\"agent\":\""
            + AGENT
            + "\"}}}";

    ResponseEntity<String> response = postJson(request, TOKEN, null);

    assertEquals(200, response.getStatusCode().value(), "message/send 应 200，而不是旧缺陷的 500");
    MediaType contentType = response.getHeaders().getContentType();
    assertNotNull(contentType, "JSON-RPC 响应应带 Content-Type");
    assertTrue(
        MediaType.APPLICATION_JSON.includes(contentType),
        "JSON-RPC 响应应是 application/json：" + contentType);

    JsonNode body = mapper.readTree(response.getBody());
    assertEquals("2.0", body.get("jsonrpc").asText(), "JSON-RPC 2.0 信封");
    assertEquals(1, body.get("id").asLong(), "id 应原样回带");
    assertFalse(body.hasNonNull("error"), "成功往返不应带 error：" + body.get("error"));
    JsonNode result = body.get("result");
    assertNotNull(result, "成功往返应有 result");
    assertEquals("message", result.get("kind").asText(), "A2A 结果应是 message");
    assertEquals("agent", result.get("role").asText(), "A2A 结果角色应是 agent");
    assertFalse(result.get("parts").get(0).get("text").asText().isBlank(), "result 文本应非空");
  }

  @Test
  @DisplayName("JSONRPC非法JSON_返回PARSE_ERROR而非500")
  void jsonRpc_malformedJson_returnsParseError() throws Exception {
    ResponseEntity<String> response = postJson("{not json", TOKEN, null);

    assertEquals(200, response.getStatusCode().value(), "非法 JSON 属于 JSON-RPC 层错误，不应是 HTTP 500");
    JsonNode body = mapper.readTree(response.getBody());
    assertEquals("2.0", body.get("jsonrpc").asText(), "JSON-RPC 2.0 信封");
    assertEquals(-32700, body.get("error").get("code").asInt(), "应为 PARSE_ERROR");
  }

  @Test
  @DisplayName("JSONRPC流方法非法参数_返回JSONRPC错误_不打开SSE")
  void jsonRpc_streamWithInvalidParams_returnsJsonRpcError() throws Exception {
    ResponseEntity<String> response =
        postJson(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"message/stream\",\"params\":{}}",
            TOKEN,
            null);

    assertEquals(200, response.getStatusCode().value(), "预检失败不应开流，走普通 HTTP 响应");
    JsonNode body = mapper.readTree(response.getBody());
    assertEquals(7, body.get("id").asInt(), "错误响应应回带请求 id");
    assertEquals(-32602, body.get("error").get("code").asInt(), "缺 message 应返回 INVALID_PARAMS");
  }

  @Test
  @DisplayName("跳数超限_403_hop过滤器装配")
  void jsonRpc_hopOverflow_is403() throws Exception {
    ResponseEntity<String> response = postJson("{}", TOKEN, "4");

    assertEquals(403, response.getStatusCode().value(), "hop 4 > max 3 应 403");
    assertTrue(
        response.getBody() != null && response.getBody().contains("hop limit exceeded"),
        "403 响应体应说明跳数超限：" + response.getBody());
  }

  private ResponseEntity<String> postJson(String json, String token, String hop) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    if (hop != null) {
      headers.set("X-A2A-Hop", hop);
    }
    return rest.postForEntity(RPC_PATH, new HttpEntity<>(json, headers), String.class);
  }
}
