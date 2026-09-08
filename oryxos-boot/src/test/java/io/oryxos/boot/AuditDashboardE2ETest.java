package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmCallRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 016 端到端：mock provider 启动整台服务（真实 HTTP + SQLite + ReAct + 审计），从测试里自己配模型定价、发起对话， 再查审计报表接口——验证「配价 →
 * 触发调用 → 审计查询」的完整链路。无 key、无网络、gate 内可跑。
 *
 * <p>mock provider 不返回 usage，故成本为「未计量」（costMicros=null）；成本金额的精确换算由 CostComputeTest 覆盖。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class AuditDashboardE2ETest {

  private static final Path ROOT = seedWorkspace();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCalls;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-audit-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("mock-agent"));
      Files.writeString(
          root.resolve("agents/mock-agent/AGENT.md"),
          """
          ---
          name: mock-agent
          description: mock 自测 Agent
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
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("audit-e2e.db"));
  }

  @Test
  @DisplayName("配模型定价_触发对话_再查审计报表接口")
  void configurePricing_driveConversation_thenQueryAuditDashboard() throws Exception {
    // ① 配模型定价（mock/mock-model）
    JsonNode pricing =
        postData(
            "/api/v1/pricing",
            "{\"provider\":\"mock\",\"model\":\"mock-model\",\"promptPrice\":1.0,\"completionPrice\":2.0}");
    assertTrue(pricing.hasNonNull("id"), "定价应有 id");

    // ② 定价查得回
    JsonNode pricingList = getData("/api/v1/pricing");
    assertTrue(
        jsonStream(pricingList)
            .anyMatch(
                n ->
                    "mock".equals(n.get("provider").asText())
                        && "mock-model".equals(n.get("model").asText())),
        "定价列表应含刚配的 mock/mock-model");
    assertTrue(
        jsonStream(pricingList)
            .filter(n -> "mock".equals(n.get("provider").asText()))
            .findFirst()
            .map(n -> n.get("promptPrice").asDouble() == 1.0)
            .orElse(false),
        "输入单价应为 1.0");

    // ③ 创建会话并发起对话（mock 驱动两轮 ReAct：save_memory + 收尾）
    String sessionId =
        postData("/api/v1/sessions", "{\"profile\":\"mock-agent\"}").get("sessionId").asText();
    assertFalse(sessionId.isBlank(), "应拿到 sessionId");
    JsonNode reply =
        postData("/api/v1/sessions/" + sessionId + "/messages", "{\"content\":\"记住：我在北京，怕冷\"}");
    assertFalse(reply.get("reply").asText().isBlank(), "应有非空最终答复");

    // ④ LLM 汇总：count>=2（两轮 ReAct）、成功率 100%（mock 不失败）、成本未计量（mock 无 usage）
    JsonNode llmSummary = getData("/api/v1/audit/llm/summary");
    assertTrue(llmSummary.get("count").asLong() >= 2, "LLM 调用应至少 2 条");
    assertEquals(1.0, llmSummary.get("successRate").asDouble(), "mock 调用成功率应为 1.0");
    assertEquals(0, llmSummary.get("totalCostMicros").asLong(), "mock 返回 0 token，成本应为 0（零用量，非未计量）");

    // ⑤ 按模型分组：mock-model 出现
    JsonNode byModel = getData("/api/v1/audit/llm/by-model");
    assertTrue(
        jsonStream(byModel).anyMatch(n -> "mock-model".equals(n.get("key").asText())),
        "按模型分组应含 mock-model");

    // ⑥ 按 Agent 分组：mock-agent 出现（Agent 归属正确）
    JsonNode byAgent = getData("/api/v1/audit/by-agent");
    assertTrue(
        jsonStream(byAgent).anyMatch(n -> "mock-agent".equals(n.get("key").asText())),
        "按 Agent 分组应含 mock-agent");

    // ⑦ Agent 归属落库：llm_calls.profileName == mock-agent
    assertTrue(
        llmCalls.findBySessionId(sessionId).stream()
            .allMatch(c -> "mock-agent".equals(c.getProfileName())),
        "llm_calls 的 profileName 应冗余为发起 Agent");
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

  private static java.util.stream.Stream<JsonNode> jsonStream(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false);
  }
}
