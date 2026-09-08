package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.session.SessionManager;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.web.config.WebApiKeyProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 019 端到端：mock provider（已支持分段流式，R7）+ 真实 HTTP + SQLite 验证流式全链路——多 token 且拼接 == done.reply、tool
 * 事件成对、SC-002 弱化断言（token 数 &gt;1 且首 token 早于 done）、掐断连接后会话历史与审计完整
 * （V5/SC-004）、流式与非流式审计条数同口径（V10/SC-007）、018 门禁复验（V7/SC-005，运行时开关模式同 ApiKeyAuthE2ETest）。无
 * key、无网络、gate 内可跑。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class SseStreamingE2ETest {

  private static final Path ROOT = seedWorkspace();

  private static final Pattern EVENT_PATTERN =
      Pattern.compile("event: (\\w+)\\ndata: (\\{.*?\\})\\n\\n", Pattern.DOTALL);

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private SessionManager sessionManager;
  @Autowired private LlmCallRepository llmCalls;
  @Autowired private WebApiKeyProperties apiKeyProperties;
  @LocalServerPort private int port;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-sse-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("mock-agent"));
      Files.writeString(
          root.resolve("agents/mock-agent/AGENT.md"),
          """
          ---
          name: mock-agent
          description: mock 流式自测 Agent
          identity:
            agent_name: mock小欧
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
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("sse-e2e.db"));
  }

  @Test
  void streaming_fullChain_tokensToolsDone_thenAuditParity() throws Exception {
    String sessionId = createSession("user-stream");

    // —— 流式发送：mock 第一轮调 save_memory、第二轮分段流出终局文本 ——
    ResponseEntity<String> streamed =
        exchange(sessionId, "记住我喜欢咖啡", MediaType.TEXT_EVENT_STREAM_VALUE);
    assertEquals(HttpStatus.OK, streamed.getStatusCode());
    assertTrue(streamed.getHeaders().getContentType().toString().startsWith("text/event-stream"));

    List<String[]> events = parseEvents(streamed.getBody());
    List<String[]> tokens = events.stream().filter(e -> e[0].equals("token")).toList();
    // SC-002 弱化断言：token 事件数 > 1，且首个 token 早于 done（事件序即到达序）
    assertTrue(tokens.size() > 1, "应有多个 token 事件（mock 分段），实际 " + tokens.size());
    assertEquals("done", events.get(events.size() - 1)[0], "恰好以 done 终结");
    assertTrue(
        events.stream().filter(e -> e[0].equals("done") || e[0].equals("error")).count() == 1,
        "终结事件恰好一个");
    // tool 事件成对（mock 第一轮 save_memory）
    assertTrue(
        events.stream().anyMatch(e -> e[0].equals("tool_start") && e[1].contains("save_memory")));
    assertTrue(
        events.stream().anyMatch(e -> e[0].equals("tool_end") && e[1].contains("save_memory")));
    // 拼接一致（FR-004/SC-003）
    String joined =
        tokens.stream()
            .map(e -> mapper.convertValue(readJson(e[1]).get("delta"), String.class))
            .reduce("", String::concat);
    String doneReply = readJson(events.get(events.size() - 1)[1]).get("reply").asText();
    assertEquals(doneReply, joined);

    long streamedCalls = llmCalls.findBySessionId(sessionId).size();
    assertTrue(streamedCalls >= 2, "两轮 ReAct 应至少落 2 条 llm_calls，实际 " + streamedCalls);

    // —— 审计同口径（SC-007）：另一会话非流式发同样消息，llm_calls 条数一致 ——
    String plainSession = createSession("user-plain");
    ResponseEntity<String> plain =
        exchange(plainSession, "记住我喜欢咖啡", MediaType.APPLICATION_JSON_VALUE);
    assertEquals(HttpStatus.OK, plain.getStatusCode());
    assertEquals(streamedCalls, (long) llmCalls.findBySessionId(plainSession).size());
  }

  @Test
  void clientDisconnect_processingCompletesAndPersists() throws Exception {
    String sessionId = createSession("user-disconnect");
    long callsBefore = llmCalls.findBySessionId(sessionId).size();

    // 原生连接读首字节后立刻断开（模拟客户端掐线）
    HttpURLConnection connection =
        (HttpURLConnection)
            URI.create("http://127.0.0.1:" + port + "/api/v1/sessions/" + sessionId + "/messages")
                .toURL()
                .openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Accept", "text/event-stream");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);
    connection
        .getOutputStream()
        .write("{\"content\":\"记住我喜欢断线测试\"}".getBytes(StandardCharsets.UTF_8));
    try (InputStream in = connection.getInputStream()) {
      in.read(); // 确认流已建立
    }
    connection.disconnect();

    // FR-008/SC-004：服务端照常完成——轮询等待会话历史出现最终回复与审计落库
    String expectedReply = "好的，已经按你的要求记录并处理完成。";
    boolean completed = false;
    for (int i = 0; i < 40 && !completed; i++) {
      Thread.sleep(250);
      completed =
          sessionManager.get(sessionId).map(s -> s.messages()).stream()
                  .flatMap(List::stream)
                  .anyMatch(m -> expectedReply.equals(m.content()))
              && llmCalls.findBySessionId(sessionId).size() >= callsBefore + 2;
    }
    assertTrue(completed, "断开后本轮应照常完成：回复落会话历史、llm_calls 照写");
  }

  @Test
  void apiKeyGate_appliesToStreamingRequests() {
    String sessionId = createSession("user-gate");
    apiKeyProperties.setEnabled(true);
    try {
      ResponseEntity<String> denied = exchange(sessionId, "hi", MediaType.TEXT_EVENT_STREAM_VALUE);
      assertEquals(HttpStatus.UNAUTHORIZED, denied.getStatusCode());
      assertTrue(denied.getBody() != null && denied.getBody().contains("\"code\":401"));
    } finally {
      apiKeyProperties.setEnabled(false);
    }
  }

  /** 会话按 channel:user:profile 三元组幂等——各测试用独立 userId 隔离审计计数。 */
  private String createSession(String userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> created =
        rest.postForEntity(
            "/api/v1/sessions",
            new HttpEntity<>("{\"profile\":\"mock-agent\",\"userId\":\"" + userId + "\"}", headers),
            String.class);
    assertEquals(HttpStatus.OK, created.getStatusCode());
    return readJson(created.getBody()).get("data").get("sessionId").asText();
  }

  private ResponseEntity<String> exchange(String sessionId, String content, String accept) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.ACCEPT, accept);
    return rest.exchange(
        "/api/v1/sessions/" + sessionId + "/messages",
        HttpMethod.POST,
        new HttpEntity<>("{\"content\":\"" + content + "\"}", headers),
        String.class);
  }

  private JsonNode readJson(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (IOException e) {
      throw new IllegalStateException("invalid json: " + raw, e);
    }
  }

  private static List<String[]> parseEvents(String raw) {
    List<String[]> events = new ArrayList<>();
    Matcher matcher = EVENT_PATTERN.matcher(raw == null ? "" : raw);
    while (matcher.find()) {
      events.add(new String[] {matcher.group(1), matcher.group(2)});
    }
    return events;
  }
}
