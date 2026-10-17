package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.a2a.A2aAgentCardService;
import io.oryxos.core.a2a.A2aMessageService;
import io.oryxos.core.a2a.A2aProperties;
import io.oryxos.core.a2a.A2aRemoteClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A2A 缺省态端到端：不配 {@code oryxos.a2a.enabled}（默认 false），验证开关关掉时 A2A 的条件组件一个都不存在， 两个端点是 404——与 {@link
 * A2aStartupE2ETest} 的打开态构成对照，防止「条件注解放错位置导致永远注册」或「端点还在而服务已关」的半开状态。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class A2aDisabledE2ETest {

  private static final Path ROOT = seedWorkspace();

  @Autowired private TestRestTemplate rest;
  @Autowired private ApplicationContext context;
  @Autowired private A2aProperties a2aProperties;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-a2a-disabled-e2e");
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
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("a2a-disabled.db"));
  }

  @Test
  @DisplayName("缺省关闭_条件组件一个都不装配")
  void switchOff_contextHasNoA2aComponents() {
    assertFalse(a2aProperties.enabled(), "未配置时 oryxos.a2a.enabled 应为默认 false");
    assertEquals(0, context.getBeanNamesForType(A2aAgentCardService.class).length, "卡片服务不应存在");
    assertEquals(0, context.getBeanNamesForType(A2aMessageService.class).length, "消息服务不应存在");
    assertEquals(0, context.getBeanNamesForType(A2aRemoteClient.class).length, "远端客户端不应存在");
    assertEquals(
        0,
        context.getBeanNamesForType(io.oryxos.web.controller.A2aAgentCardController.class).length,
        "卡片控制器不应存在");
    assertEquals(
        0,
        context.getBeanNamesForType(io.oryxos.web.controller.A2aJsonRpcController.class).length,
        "JSON-RPC 控制器不应存在");
  }

  @Test
  @DisplayName("缺省关闭_卡片端点404")
  void switchOff_agentCardPathIs404() {
    ResponseEntity<String> response =
        rest.getForEntity("/.well-known/agent-card.json", String.class);

    assertEquals(404, response.getStatusCode().value(), "开关关闭时卡片端点应不存在");
  }

  @Test
  @DisplayName("缺省关闭_JSONRPC端点404")
  void switchOff_jsonRpcPathIs404() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity("/api/v1/a2a", new HttpEntity<>("{}", headers), String.class);

    assertEquals(404, response.getStatusCode().value(), "开关关闭时 JSON-RPC 端点应不存在");
  }
}
