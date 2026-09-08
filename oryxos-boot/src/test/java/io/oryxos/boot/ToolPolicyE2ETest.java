package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 020 端到端：mock provider（第一轮固定调 save_memory）+ 真实 HTTP + SQLite 验证三道保险全链路——空规则现状 （SC-001）→
 * GLOBAL_DENY 后事中拒绝 + 失败回填 + 审计 blocked_by='policy' 可筛（SC-002/SC-005）→ EXEMPT 仅对 登记 Agent 放行（US2）→
 * AGENT_DENY 最终收紧（三重叠加，SC-003）→ 删规则热更新即恢复（SC-004）。无 key、无网络、 gate 内可跑。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolPolicyE2ETest {

  private static final Path ROOT = seedWorkspace();
  private static final String REPLY_OK = "好的，已经按你的要求记录并处理完成。";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private ToolInvocationRepository toolInvocations;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-policy-e2e");
      Files.createDirectories(root.resolve("memory"));
      for (String agent : new String[] {"agent-a", "agent-b"}) {
        Files.createDirectories(root.resolve("agents").resolve(agent));
        Files.writeString(
            root.resolve("agents/" + agent + "/AGENT.md"),
            """
            ---
            name: %s
            description: 策略走查 Agent
            identity:
              agent_name: 小欧
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
            """
                .formatted(agent));
      }
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("policy-e2e.db"));
  }

  @Test
  @Order(1)
  void emptyPolicy_currentBehaviorUnchanged() {
    // mock 第一轮调 save_memory 成功、第二轮收尾——零策略时全链路照旧（SC-001）
    String reply = invoke("agent-a", "记住我喜欢咖啡");
    assertEquals(REPLY_OK, reply);
    assertTrue(
        toolInvocations.findAll().stream()
            .anyMatch(t -> "save_memory".equals(t.getToolName()) && t.isSuccess()));
  }

  @Test
  @Order(2)
  void globalDeny_midGuardRejects_auditMarked_thenHotReloadRestores() {
    long ruleId = addRule("GLOBAL_DENY", null, "save_memory");
    try {
      // 事中保险：mock 模型仍会请求 save_memory（其脚本固定），执行层拒绝 → 失败回填 → 模型第二轮收尾
      String reply = invoke("agent-a", "记住被禁场景");
      assertEquals(REPLY_OK, reply); // mock 第二轮固定文案；关键断言在下方审计与零执行

      var denied =
          toolInvocations.findAll().stream()
              .filter(t -> "policy".equals(t.getBlockedBy()))
              .toList();
      assertFalse(denied.isEmpty(), "策略拒绝应落审计并带 blocked_by='policy'");
      assertTrue(denied.stream().allMatch(t -> !t.isSuccess()));
      assertTrue(denied.stream().anyMatch(t -> t.getErrorMessage().contains("被平台策略禁止")));

      // 审计筛选 API（FR-006/SC-005）
      ResponseEntity<String> filtered =
          rest.getForEntity("/api/v1/audit/tool?blockedBy=policy", String.class);
      assertEquals(HttpStatus.OK, filtered.getStatusCode());
      JsonNode rows = readJson(filtered.getBody()).get("data");
      assertTrue(rows.size() > 0);
      for (JsonNode row : rows) {
        assertEquals("policy", row.get("blockedBy").asText());
      }
    } finally {
      deleteRule(ruleId);
    }
    // 热更新：删规则后无需重启即恢复（SC-004）
    long successBefore = successfulSaveMemoryCount();
    invoke("agent-a", "记住恢复场景");
    assertTrue(successfulSaveMemoryCount() > successBefore, "删规则后 save_memory 应恢复执行");
  }

  @Test
  @Order(3)
  void exemptAndAgentDeny_tripleOverlayConverges() {
    long deny = addRule("GLOBAL_DENY", null, "save_memory");
    long exempt = addRule("AGENT_EXEMPT", "agent-a", "save_memory");
    try {
      long okBefore = successfulSaveMemoryCount();
      long deniedBefore = policyDeniedCount();

      // EXEMPT：agent-a 放行、agent-b 仍被拒（US2）
      invoke("agent-a", "例外场景A");
      invoke("agent-b", "例外场景B");
      assertTrue(successfulSaveMemoryCount() > okBefore, "agent-a 应被例外放行");
      assertTrue(policyDeniedCount() > deniedBefore, "agent-b 应仍被全局 deny 拦截");

      // AGENT_DENY 最终收紧：例外救不回（三重叠加，SC-003）
      long agentDeny = addRule("AGENT_DENY", "agent-a", "save_memory");
      try {
        long deniedMid = policyDeniedCount();
        invoke("agent-a", "三重叠加场景");
        assertTrue(policyDeniedCount() > deniedMid, "AGENT_DENY 应最终收紧 agent-a");
      } finally {
        deleteRule(agentDeny);
      }
    } finally {
      deleteRule(exempt);
      deleteRule(deny);
    }
  }

  // —— helpers ——

  private String invoke(String agent, String content) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/agents/" + agent + "/invoke",
            new HttpEntity<>("{\"content\":\"" + content + "\"}", headers),
            String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return readJson(response.getBody()).get("data").get("reply").asText();
  }

  private long addRule(String type, String agent, String pattern) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        agent == null
            ? "{\"ruleType\":\"%s\",\"pattern\":\"%s\"}".formatted(type, pattern)
            : "{\"ruleType\":\"%s\",\"agentName\":\"%s\",\"pattern\":\"%s\"}"
                .formatted(type, agent, pattern);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/tool-policy/rules", new HttpEntity<>(body, headers), String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return readJson(response.getBody()).get("data").get("id").asLong();
  }

  private void deleteRule(long id) {
    rest.delete("/api/v1/tool-policy/rules/" + id);
  }

  private long successfulSaveMemoryCount() {
    return toolInvocations.findAll().stream()
        .filter(t -> "save_memory".equals(t.getToolName()) && t.isSuccess())
        .count();
  }

  private long policyDeniedCount() {
    return toolInvocations.findAll().stream()
        .filter(t -> "policy".equals(t.getBlockedBy()))
        .count();
  }

  private JsonNode readJson(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (IOException e) {
      throw new IllegalStateException("invalid json: " + raw, e);
    }
  }
}
