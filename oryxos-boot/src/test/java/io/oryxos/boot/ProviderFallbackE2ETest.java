package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 023 端到端：broken provider（配置种子预置，base-url 指向 127.0.0.1 不通端口——启动前预置是必须的， Agent 加载时 knownProviders
 * 校验要能看到它）+ mock 备用；真实 HTTP + SQLite——主败备成用户无感知（SC-001）、 每尝试一条审计且 trace 同链（SC-003）、切换 WARN 带
 * traceId、零声明回归（SC-002）、全败上抛。 无 key、无网络、gate 内可跑。
 */
// @AutoConfigureObservability：@SpringBootTest 默认禁用 metrics export（防测试打点外泄），
// 不加则 PrometheusMeterRegistry 不装配、/actuator/prometheus 404——真机 serve 不受此影响
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oryxos.providers[0].name=mock",
      "oryxos.providers[1].name=broken",
      "oryxos.providers[1].api-key=dummy",
      "oryxos.providers[1].base-url=http://127.0.0.1:1",
      "management.endpoints.web.exposure.include=health,prometheus"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProviderFallbackE2ETest {

  private static final Path ROOT = seedWorkspace();
  private static final String REPLY_OK = "好的，已经按你的要求记录并处理完成。";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCalls;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-fallback-e2e");
      Files.createDirectories(root.resolve("memory"));
      // fb-agent：主 broken + fallback mock；plain-agent：零声明（只配 mock，SC-002 回归锚点）
      writeAgent(
          root,
          "fb-agent",
          """
          provider:
            name: broken
            model: broken-model
            fallback:
              - name: mock
                model: mock-model
          """);
      writeAgent(
          root,
          "plain-agent",
          """
          provider:
            name: mock
            model: mock-model
          """);
      writeAgent(
          root,
          "doomed-agent",
          """
          provider:
            name: broken
            model: broken-model
            fallback:
              - name: broken
                model: broken-model-2
          """);
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void writeAgent(Path root, String name, String providerYaml) throws IOException {
    Files.createDirectories(root.resolve("agents").resolve(name));
    Files.writeString(
        root.resolve("agents/" + name + "/AGENT.md"),
        """
        ---
        name: %s
        description: fallback 走查 Agent
        identity:
          agent_name: 小欧
          prompt: 你是一个测试助手。
        %s
        tools:
          - save_memory
        settings:
          max_iterations: 10
          max_history_turns: 20
        ---
        你是一个测试助手，被触发时正常回应。
        """
            .formatted(name, providerYaml.stripTrailing()));
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("fallback-e2e.db"));
    registry.add("oryxos.root", ROOT::toString);
  }

  @Test
  @Order(1)
  void 主败备成_用户无感知_审计每尝试一条且trace同链() {
    var root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    root.addAppender(appender);
    try {
      long maxIdBefore = maxLlmId();
      JsonNode data = invokeOk("fb-agent", "记住我喜欢咖啡");
      assertEquals(REPLY_OK, data.get("reply").asText()); // SC-001：备用接住，回复正常

      // SC-003：每尝试一条——mock 每轮 2 次 LLM 调用，每次主 broken 失败 + mock 成功 = 4 条同 trace
      List<LlmCall> round = llmCallsAfter(maxIdBefore);
      String traceId = data.get("traceId").asText();
      assertEquals(2, round.stream().filter(c -> "broken".equals(c.getProvider())).count());
      assertEquals(2, round.stream().filter(c -> "mock".equals(c.getProvider())).count());
      assertTrue(
          round.stream()
              .filter(c -> "broken".equals(c.getProvider()))
              .noneMatch(LlmCall::isSuccess));
      assertTrue(
          round.stream().filter(c -> "mock".equals(c.getProvider())).allMatch(LlmCall::isSuccess));
      assertTrue(round.stream().allMatch(c -> traceId.equals(c.getTraceId())), "主备尝试共享该轮 trace");
      // broken 尝试的 model 如实（不是备用的 mock-model）
      assertTrue(
          round.stream()
              .filter(c -> "broken".equals(c.getProvider()))
              .allMatch(c -> "broken-model".equals(c.getModel())));

      // 021 时间线：主备 LLM 步同链可回放
      JsonNode timeline = getJson("/api/v1/audit/trace/" + traceId).get("data");
      assertTrue(timeline.get("found").asBoolean());
      assertEquals(4, timeline.get("summary").get("llmCalls").asInt());

      // FR-006：切换 WARN 带本轮 traceId（MDC）
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getFormattedMessage().contains("provider 切换")
                          && e.getFormattedMessage().contains("broken")
                          && e.getFormattedMessage().contains("mock")
                          && traceId.equals(e.getMDCPropertyMap().get("traceId"))));
    } finally {
      root.detachAppender(appender);
    }
  }

  @Test
  @Order(2)
  void 零声明Agent_行为回归_单轮审计条数不变() {
    long maxIdBefore = maxLlmId();
    JsonNode data = invokeOk("plain-agent", "回归场景");
    assertEquals(REPLY_OK, data.get("reply").asText());

    // SC-002/SC-006：零声明单轮恰 2 条（mock 两次调用），无多余尝试
    List<LlmCall> round = llmCallsAfter(maxIdBefore);
    assertEquals(2, round.size());
    assertTrue(round.stream().allMatch(c -> "mock".equals(c.getProvider())));
    assertTrue(round.stream().allMatch(LlmCall::isSuccess));
  }

  @Test
  @Order(3)
  void 全部候选失败_报错且无成功行() {
    long maxIdBefore = maxLlmId();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/agents/doomed-agent/invoke",
            new HttpEntity<>("{\"content\":\"注定失败\"}", headers),
            String.class);
    assertTrue(response.getStatusCode().is5xxServerError(), "候选耗尽应报错（现状口径）");

    List<LlmCall> round = llmCallsAfter(maxIdBefore);
    assertFalse(round.isEmpty());
    assertTrue(round.stream().noneMatch(LlmCall::isSuccess), "无成功行");
    assertEquals(2, round.size(), "主 + 备各一次失败尝试（同一轮首个 LLM 调用即失败终止）");
  }

  @Test
  @Order(4)
  void prometheus端点_五类业务指标在位且与审计口径对照一致() {
    // 触发一次策略拦截：GLOBAL_DENY save_memory → policy_blocks 计数
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> rule =
        rest.postForEntity(
            "/api/v1/tool-policy/rules",
            new HttpEntity<>("{\"ruleType\":\"GLOBAL_DENY\",\"pattern\":\"save_memory\"}", headers),
            String.class);
    assertEquals(HttpStatus.OK, rule.getStatusCode());
    long ruleId = readJson(rule.getBody()).get("data").get("id").asLong();
    try {
      invokeOk("plain-agent", "触发拦截场景");
    } finally {
      rest.delete("/api/v1/tool-policy/rules/" + ruleId);
    }

    ResponseEntity<String> metrics = rest.getForEntity("/actuator/prometheus", String.class);
    assertEquals(HttpStatus.OK, metrics.getStatusCode());
    String body = metrics.getBody();
    assertNotNull(body);

    // 五类指标在位（SC-005）
    assertTrue(body.contains("oryxos_llm_calls_total"), "LLM 调用计数在位");
    // Prometheus 文本格式标签按字母序输出——逐行 contains 判定，不假定标签顺序
    assertTrue(
        hasSeries(body, "oryxos_llm_calls_total", "provider=\"broken\"", "outcome=\"failure\""),
        "broken 失败序列在位");
    assertTrue(
        hasSeries(body, "oryxos_llm_calls_total", "provider=\"mock\"", "outcome=\"success\""),
        "mock 成功序列在位");
    assertTrue(
        hasSeries(body, "oryxos_fallback_switches_total", "from=\"broken\"", "to=\"mock\""),
        "切换计数在位");
    assertTrue(body.contains("oryxos_tool_invocations_total"), "工具调用计数在位");
    // oryxos_llm_tokens_total：mock provider 的 usage 恒为 0 token → 指标合法缺席（契约：未发生即缺席/零值），
    // 计数语义由 MicrometerMetricsRecorderTest 钉死，真实 token 序列在真机走查（quickstart V6）验证
    assertTrue(body.contains("oryxos_policy_blocks_total"), "策略拦截计数在位");

    // SC-005/SC-006：指标总数与 llm_calls 表行数同口径（每尝试各计一）
    double metricTotal =
        java.util.regex.Pattern.compile("oryxos_llm_calls_total\\{[^}]*} (\\S+)")
            .matcher(body)
            .results()
            .mapToDouble(m -> Double.parseDouble(m.group(1)))
            .sum();
    assertEquals(llmCalls.count(), (long) metricTotal, "指标计数与审计行数对照一致");
  }

  private static boolean hasSeries(String body, String metric, String... labels) {
    return body.lines()
        .anyMatch(
            line ->
                line.startsWith(metric)
                    && java.util.Arrays.stream(labels).allMatch(line::contains));
  }

  // —— helpers ——

  private JsonNode invokeOk(String agent, String content) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/agents/" + agent + "/invoke",
            new HttpEntity<>("{\"content\":\"" + content + "\"}", headers),
            String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
    JsonNode data = readJson(response.getBody()).get("data");
    assertNotNull(data);
    return data;
  }

  private long maxLlmId() {
    return llmCalls.findAll().stream().mapToLong(LlmCall::getId).max().orElse(0);
  }

  private List<LlmCall> llmCallsAfter(long maxIdBefore) {
    return llmCalls.findAll().stream().filter(c -> c.getId() > maxIdBefore).toList();
  }

  private JsonNode getJson(String path) {
    ResponseEntity<String> response = rest.getForEntity(path, String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return readJson(response.getBody());
  }

  private JsonNode readJson(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (IOException e) {
      throw new IllegalStateException("invalid json: " + raw, e);
    }
  }
}
