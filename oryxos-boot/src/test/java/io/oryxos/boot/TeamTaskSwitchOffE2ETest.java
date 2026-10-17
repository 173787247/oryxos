package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code oryxos.task.team.enabled=false}（默认档）的对照测试：{@code /api/v1/team-tasks} 必须不存在。
 *
 * <p>它与 {@link TeamTaskSwitchE2ETest} 成对：同一套断言在开关两种取值下得到相反结果，才证明端点是由开关登记/注销的， 而不是永远注册。context 缓存键因
 * properties 不同而分开。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock", "oryxos.task.team.enabled=false"})
class TeamTaskSwitchOffE2ETest {

  private static final Path ROOT = seedWorkspace();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-team-task-off");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents"));
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) {
    registry.add("oryxos.root", ROOT::toString);
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("team-task-off.db"));
  }

  @Test
  @DisplayName("开关关闭_context 起得来_但 team-tasks 端点不存在")
  void switchOff_endpointIsAbsent() throws Exception {
    // 对照：同一个 context 里别的端点正常——404 不是"服务没起来"，而是这个端点确实没注册
    ResponseEntity<String> control = rest.getForEntity("/api/v1/agents", String.class);
    assertEquals(200, control.getStatusCode().value(), "其它端点应正常，context 是起得来的");
    JsonNode controlBody = mapper.readTree(control.getBody());
    assertEquals(0, controlBody.get("code").asInt(), "统一信封 code 应为 0");

    // 开关关：控制器没注册，路径落不到任何 handler → 404（这里只断言 GET；未注册路径上 POST 的
    // 表现由静态资源 handler 决定，可能是 405，不作为"开关生效"的判据）
    ResponseEntity<String> get = rest.getForEntity("/api/v1/team-tasks", String.class);
    assertEquals(404, get.getStatusCode().value(), "开关关闭时 GET /api/v1/team-tasks 应 404");
    assertEquals(404, mapper.readTree(get.getBody()).get("code").asInt(), "404 也应走统一信封");
  }
}
