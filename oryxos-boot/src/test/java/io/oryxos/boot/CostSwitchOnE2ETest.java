package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code oryxos.cost.enabled=true} 的启动路径：账本在真实请求链上留痕、成本看板查得到。
 *
 * <p>这套开关此前**没有任何测试打开过**（全仓 `grep 'oryxos.cost.enabled'` 于测试目录为空）：装饰器 （{@code
 * CostAwareLlmCallAuditor} / {@code CostAwareToolInvocationAuditor}）是无条件装配的，开关只在账本内部 生效 ——
 * 也就是说"打开之后账本到底记不记、看板查不查得到"从来没在真实上下文里验证过。
 *
 * <p>装配沿用 {@code AuditDashboardE2ETest} 的形状（同样配定价、建会话、发一条消息驱动 mock 两轮 ReAct）， 断言落在成本账本与 {@code
 * /api/v1/cost/**} 上。mock provider 不返回 usage ⇒ 金额为 0，但**账本行必须存在**。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock", "oryxos.cost.enabled=true"})
class CostSwitchOnE2ETest {

  private static final Path ROOT = seedWorkspace();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-cost-on-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("cost-agent"));
      Files.writeString(
          root.resolve("agents/cost-agent/AGENT.md"),
          """
          ---
          name: cost-agent
          description: 成本账本自测 Agent
          identity:
            agent_name: 成本小欧
            prompt: 你是一个测试助手。
          provider:
            name: mock
            model: mock-model
          tools:
            - save_memory
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
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("cost-on.db"));
  }

  @Test
  @DisplayName("开关打开_一轮对话后账本留痕且看板查得到")
  void enabledSwitchRecordsTheTurnAndTheDashboardReadsIt() throws Exception {
    // 配定价：让记账时能解析到价目（金额本身仍是 0，因为 mock 不返回 usage）
    JsonNode pricing =
        postData(
            "/api/v1/pricing",
            "{\"provider\":\"mock\",\"model\":\"mock-model\",\"promptPrice\":1.0,\"completionPrice\":2.0}");
    assertTrue(pricing.hasNonNull("id"), "定价应有 id");

    // 一轮真实对话（mock 驱动两轮 ReAct）
    String sessionId =
        postData("/api/v1/sessions", "{\"profile\":\"cost-agent\"}").get("sessionId").asText();
    assertFalse(sessionId.isBlank(), "应拿到 sessionId");
    JsonNode reply =
        postData("/api/v1/sessions/" + sessionId + "/messages", "{\"content\":\"你好\"}");
    assertFalse(reply.get("reply").asText().isBlank(), "应有非空最终答复");

    // ① 账本口径：两轮 ReAct ⇒ 至少 2 次 LLM 记账，且明细非空
    JsonNode attribution = getData("/api/v1/cost/attribution");
    assertTrue(
        attribution.get("llmCallCount").asLong() >= 2,
        "开关打开时一轮对话应至少留 2 条 LLM 账本行，实际 " + attribution.get("llmCallCount").asLong());
    assertFalse(attribution.get("entries").isEmpty(), "明细 entries 不应为空（看板下钻依赖它）");
    assertEquals(
        0L, attribution.get("totalCostMicros").asLong(), "mock 返回 0 token ⇒ 金额为 0（零用量，不是未计量）");

    // ② 日汇总：今天有一行（该端点要求 from/to，缺参数会 400）
    String from = java.time.Instant.now().minus(java.time.Duration.ofDays(1)).toString();
    String to = java.time.Instant.now().plus(java.time.Duration.ofDays(1)).toString();
    JsonNode daily = getData("/api/v1/cost/daily?from=" + from + "&to=" + to);
    assertFalse(daily.isEmpty(), "当日汇总应有一行");
    assertTrue(
        daily.get(0).hasNonNull("date") && daily.get(0).hasNonNull("llmCostMicros"),
        "汇总行应含 date 与 llmCostMicros");

    // ③ facets：形状可用（看板的筛选项来源）
    JsonNode facets = getData("/api/v1/cost/facets");
    assertTrue(facets.isObject(), "facets 应是对象");
  }

  private JsonNode postData(String path, String json) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return dataOf(rest.postForEntity(path, new HttpEntity<>(json, headers), String.class));
  }

  private JsonNode getData(String path) throws Exception {
    return dataOf(rest.getForEntity(path, String.class));
  }

  private JsonNode dataOf(ResponseEntity<String> resp) throws Exception {
    assertEquals(200, resp.getStatusCode().value(), "HTTP 应 200");
    JsonNode body = mapper.readTree(resp.getBody());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应为 0");
    return body.get("data");
  }
}
