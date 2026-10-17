package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 出网形状端到端：Boot 4 / Spring 7 的 MVC JSON 转换器是 Jackson 3（{@code tools.jackson}）， 它不认识 Jackson 2（{@code
 * com.fasterxml}）的 {@code JsonNode}——按 POJO 序列化，发出去的是 {@code {"array":false,"nodeType":"OBJECT",…}}
 * 这种属性转储，真正的 JSON 内容全丢，且不报错、不改状态码。
 *
 * <p>两条出网路径各钉一条断言，且都查内容而不是只查 200：
 *
 * <ul>
 *   <li>{@code GET /api/v1/evals/export/trace/{traceId}} 的 {@code data} 必须是 trace 主题对象（含 {@code
 *       name}/{@code cases}）；
 *   <li>{@code GET /api/v1/runs/{id}/events} 的 {@code payload} 必须是事件载荷（含 {@code delta}）。
 * </ul>
 *
 * <p>工作区 / SQLite 都用临时目录（mock provider，无 key、无网络），gate 内可跑。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class JsonWireShapeE2ETest {

  private static final Path ROOT = seedWorkspace();
  private static final String AGENT = "wire-agent";
  private static final String TRACE_ID = "wire-shape-trace";
  private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(20);

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCalls;
  @Autowired private ToolInvocationRepository toolInvocations;

  /** 临时工作区：一个 provider: mock 的 Agent + 空 memory 目录；并把 oryxos.root 指过来。 */
  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-json-wire-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve(AGENT));
      Files.writeString(
          root.resolve("agents/" + AGENT + "/AGENT.md"),
          """
          ---
          name: wire-agent
          description: 出网 JSON 形状自测 Agent
          identity:
            agent_name: 形状小欧
            prompt: 你是一个测试助手。
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
          你是一个测试助手，被触发时正常回应。
          """);
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("json-wire.db"));
  }

  @Test
  @DisplayName("eval 导出：data 是 trace 主题对象，不是 Jackson 2 节点的属性转储")
  void evalExportSendsTheTraceObject() throws Exception {
    seedTrace();

    ResponseEntity<String> response =
        rest.getForEntity("/api/v1/evals/export/trace/" + TRACE_ID, String.class);
    assertEquals(200, response.getStatusCode().value(), response.getBody());
    String raw = response.getBody();
    JsonNode body = mapper.readTree(raw);
    assertEquals(0, body.get("code").asInt(), raw);

    JsonNode data = body.get("data");
    assertTrue(data.isObject(), "data 应是 JSON 对象，实际：" + raw);
    assertFalse(data.has("nodeType"), "data 是 Jackson 2 JsonNode 的属性转储（内容已丢），实际：" + raw);
    assertEquals("audit-trace-" + TRACE_ID, data.get("name").asText(), raw);

    JsonNode cases = data.get("cases");
    assertTrue(cases.isArray() && cases.size() == 1, "导出应含一条 case，实际：" + raw);
    JsonNode firstCase = cases.get(0);
    assertEquals("trace-" + TRACE_ID, firstCase.get("id").asText(), raw);
    assertEquals("save_memory", firstCase.get("actualTools").get(0).asText(), raw);
  }

  @Test
  @DisplayName("run events：payload 是事件载荷对象，不是 Jackson 2 节点的属性转储")
  void runEventsSendTheRealPayload() throws Exception {
    long runId = startRun();

    JsonNode event = awaitEvent(runId, "MESSAGE_CONTENT");
    JsonNode payload = event.get("payload");
    assertTrue(payload.isObject(), "payload 应是 JSON 对象，实际：" + event);
    assertFalse(payload.has("nodeType"), "payload 是 Jackson 2 JsonNode 的属性转储（内容已丢），实际：" + event);
    assertEquals(1, payload.get("schemaVersion").asInt(), event.toString());
    String delta = payload.path("delta").asText("");
    assertTrue(delta.contains("记录并处理完成"), "payload.delta 应是真实答复，实际：" + event);
  }

  /** 一条 trace 的审计行（LLM + 工具各一行），导出端点据此生成 suite。 */
  private void seedTrace() {
    LlmCall call = new LlmCall();
    call.setSessionId("wire-session");
    call.setProvider("mock");
    call.setModel("mock-model");
    call.setProfileName(AGENT);
    call.setTraceId(TRACE_ID);
    call.setSuccess(true);
    call.setDurationMs(12L);
    llmCalls.saveAndFlush(call);

    ToolInvocation tool = new ToolInvocation();
    tool.setSessionId("wire-session");
    tool.setToolName("save_memory");
    tool.setProfileName(AGENT);
    tool.setTraceId(TRACE_ID);
    tool.setSuccess(true);
    tool.setDurationMs(3L);
    toolInvocations.saveAndFlush(tool);
  }

  private long startRun() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> created =
        rest.postForEntity(
            "/api/v1/runs",
            new HttpEntity<>("{\"agentName\":\"" + AGENT + "\",\"content\":\"记住我喜欢咖啡\"}", headers),
            String.class);
    assertEquals(200, created.getStatusCode().value(), created.getBody());
    return mapper.readTree(created.getBody()).get("data").get("id").asLong();
  }

  /** 轮询事件列表直到出现指定类型——执行在虚拟线程里跑，事件先落库再可读。 */
  private JsonNode awaitEvent(long runId, String type) throws Exception {
    long deadline = System.nanoTime() + EVENT_TIMEOUT.toNanos();
    String lastSeen = "无";
    while (System.nanoTime() < deadline) {
      ResponseEntity<String> response =
          rest.getForEntity("/api/v1/runs/" + runId + "/events", String.class);
      assertEquals(200, response.getStatusCode().value(), response.getBody());
      JsonNode events = mapper.readTree(response.getBody()).get("data").get("events");
      for (JsonNode event : events) {
        if (type.equals(event.get("type").asText())) {
          return event;
        }
      }
      lastSeen = events.toString();
      Thread.sleep(100L);
    }
    throw new AssertionError(
        "run " + runId + " 在 " + EVENT_TIMEOUT + " 内没有 " + type + " 事件：" + lastSeen);
  }
}
