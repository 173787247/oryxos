package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code oryxos.task.team.enabled=true} 的启动测试：把整套服务在这个开关打开时真的跑一遍。
 *
 * <p>为什么需要它：{@code TeamTaskApiController} 只在这个开关打开时才注册，而全仓测试此前从未打开过它——它要是坏了（bean
 * 缺失、迁移假设不符、依赖没装配），CI 永远是绿的，用户一开就炸。
 *
 * <p>模型用脚本化的 {@link ProviderService}（{@code @Primary}，同 {@code
 * ProviderDefaultSelectionTest}）。只有"模型"是假的： ReAct / 编排 / 波次扇出 / JPA 持久化 / HTTP 全走真实路径。协调者第一次调用返回计划
 * JSON，之后返回摘要；专家按名返回固定答复。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock", "oryxos.task.team.enabled=true"})
// 显式 @Import：嵌套 @TestConfiguration 在这里不会被自动注册（不写这行时跑的是真实 mock provider）。
@Import(TeamTaskSwitchE2ETest.ScriptedProviderConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TeamTaskSwitchE2ETest {

  private static final Path ROOT = seedWorkspace();
  private static final String COORDINATOR = "team-coord";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  /** 临时工作区：一个协调者 + 两个专家（provider 名必须是注册表里有的 mock）。 */
  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-team-task-e2e");
      Files.createDirectories(root.resolve("memory"));
      seedAgent(root, "team-coord", "团队任务协调者", "你负责把目标拆成专家子任务。");
      seedAgent(root, "worker-a", "写作专家", "你负责起草。");
      seedAgent(root, "worker-b", "复核专家", "你负责复核。");
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void seedAgent(Path root, String name, String displayName, String prompt)
      throws IOException {
    Path dir = Files.createDirectories(root.resolve("agents").resolve(name));
    Files.writeString(
        dir.resolve("AGENT.md"),
        """
        ---
        name: %s
        description: team-task E2E %s
        identity:
          agent_name: %s
          prompt: %s
        provider:
          name: mock
          model: mock-model
        tools: []
        settings:
          max_iterations: 6
          max_history_turns: 12
        ---
        %s
        """
            .formatted(name, name, displayName, prompt, prompt));
  }

  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) {
    registry.add("oryxos.root", ROOT::toString);
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("team-task.db"));
  }

  @Test
  @Order(1)
  @DisplayName("开关打开_context起得来_GET列表可达到且为空")
  void switchOn_bootsAndListsEmpty() throws Exception {
    JsonNode list = getData("/api/v1/team-tasks");
    assertTrue(list.isArray(), "GET /api/v1/team-tasks 应返回数组");
    assertEquals(0, list.size(), "还没创建过任务，列表应为空");
  }

  @Test
  @Order(2)
  @DisplayName("开关打开_HTTP创建一条任务_列表与按id查询都能拿回同一条")
  void switchOn_createThenListAndGetRoundTrips() throws Exception {
    String goal = "把季度报告拆成写作与复核两步";
    JsonNode created =
        postData(
            "/api/v1/team-tasks",
            "{\"goal\":\"" + goal + "\",\"coordinator\":\"" + COORDINATOR + "\"}");

    String id = created.get("id").asText();
    assertFalse(id.isBlank(), "创建应返回任务 id");
    assertEquals(goal, created.get("goal").asText(), "goal 应原样回显");
    assertEquals(COORDINATOR, created.get("coordinator").asText(), "coordinator 应原样回显");

    // 计划真的被解析执行：两个专家各跑一次，回复进了 workers
    assertEquals(2, created.get("workers").size(), "计划里的两个专家都应执行");
    assertEquals("worker-a", created.get("workers").get(0).get("agent").asText());
    assertEquals("worker-b", created.get("workers").get(1).get("agent").asText());
    assertTrue(
        created.get("workers").get(0).get("reply").asText().contains("worker-a"), "专家应真的被调用");
    assertEquals("coordinator summary", created.get("summary").asText(), "摘要应由协调者收尾产出");

    // 持久化：列表与按 id 查询（走 JPA store + V25 迁移建的表）都能拿回同一条
    JsonNode list = getData("/api/v1/team-tasks");
    assertEquals(1, list.size(), "创建后列表应有 1 条");
    assertEquals(id, list.get(0).get("id").asText());
    assertEquals(goal, list.get(0).get("goal").asText());

    JsonNode fetched = getData("/api/v1/team-tasks/" + id);
    assertEquals(id, fetched.get("id").asText());
    assertEquals(goal, fetched.get("goal").asText());
    assertTrue(fetched.get("planRaw").asText().contains("\"subtasks\""), "计划原文应随任务持久化");
    assertEquals("coordinator summary", fetched.get("summary").asText(), "summary 应持久化");
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

  @TestConfiguration
  static class ScriptedProviderConfiguration {

    @Bean
    @Primary
    ScriptedProviderService scriptedProviderService() {
      return new ScriptedProviderService();
    }
  }

  /** 脚本化模型：协调者首轮给计划、之后给摘要；专家给固定答复。不连网、无 key。 */
  static final class ScriptedProviderService implements ProviderService {

    private static final String PLAN_JSON =
        "{\"subtasks\":["
            + "{\"agent\":\"worker-a\",\"message\":\"draft the quarterly report\"},"
            + "{\"agent\":\"worker-b\",\"message\":\"review the quarterly report\"}]}";

    private final AtomicInteger coordinatorCalls = new AtomicInteger();

    @Override
    public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
      if (COORDINATOR.equals(profile.name())) {
        return new ProviderResponse(
            coordinatorCalls.incrementAndGet() == 1 ? PLAN_JSON : "coordinator summary",
            List.of(),
            null);
      }
      return new ProviderResponse("handled by " + profile.name(), List.of(), null);
    }
  }
}
