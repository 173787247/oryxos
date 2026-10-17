package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.CostLedgerEntryRepository;
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
 * {@code oryxos.cost.enabled} 的缺省态（false）：业务照常跑，但**成本看板关着、账本不记**。
 *
 * <p>与 {@link CostSwitchOnE2ETest} 成对：同一个对话流程、同一个 Agent，唯一的差别是开关。两条一起才能说明
 * "打开开关之后多出来的东西确实来自开关"，而不是来自请求本身。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class CostSwitchOffE2ETest {

  private static final Path ROOT = seedWorkspace();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private CostLedgerEntryRepository ledgerEntries;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-cost-off-e2e");
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
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("cost-off.db"));
  }

  @Test
  @DisplayName("开关缺省_对话照常但成本端点 404 且账本不记")
  void disabledSwitchKeepsTheFeatureOff() throws Exception {
    String sessionId =
        postData("/api/v1/sessions", "{\"profile\":\"cost-agent\"}").get("sessionId").asText();
    assertFalse(sessionId.isBlank(), "应拿到 sessionId");
    JsonNode reply =
        postData("/api/v1/sessions/" + sessionId + "/messages", "{\"content\":\"你好\"}");
    assertFalse(reply.get("reply").asText().isBlank(), "开关关着不影响业务对话");

    // ① 端点关着：requireEnabled 抛 ResourceNotFoundException ⇒ 404
    ResponseEntity<String> attribution =
        rest.getForEntity("/api/v1/cost/attribution", String.class);
    assertEquals(404, attribution.getStatusCode().value(), "成本看板关着时应 404");

    // ② 账本真的没有行（不是"记了但查不到"）
    assertEquals(0L, ledgerEntries.count(), "开关关着时不应产生任何账本行");
  }

  private JsonNode postData(String path, String json) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> resp =
        rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    assertEquals(200, resp.getStatusCode().value(), "HTTP 应 200");
    JsonNode body = mapper.readTree(resp.getBody());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应为 0");
    return body.get("data");
  }
}
