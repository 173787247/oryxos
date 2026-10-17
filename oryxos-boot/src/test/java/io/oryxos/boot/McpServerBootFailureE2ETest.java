package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * MCP 连不上时的**降级**：外部依赖的可用性不是自己的可用性。
 *
 * <p>{@code McpClientService.connect} 的契约是"transport 不受支持、连接失败都只记 WARN + 落 {@code lastErrors}，
 * 不抛异常"，{@code connectAll} 又是 {@code toolRegistry} bean 工厂里的最后一步 —— 也就是说一个配错的 MCP server
 * **不应该**让整个应用起不来。这条设计此前没有测试（{@code McpServerBootE2ETest} 只跑通路径）。
 *
 * <p>做法：{@code mcp_servers.yaml} 里的 {@code command} 指向一个不存在的可执行文件 —— 进程根本拉不起来。 断言应用照常起来、内置工具还在、而那个
 * MCP 工具不在注册表里。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class McpServerBootFailureE2ETest {

  private static final Path ROOT = seedWorkspace();

  private static final String SERVER_NAME = "broken";

  private static final String TOOL_NAME = "echo";

  /** 内置工具：用来证明"其它工具没被连累"（{@code FileTools} 注册的名字见 {@code OryxOsRuntime}）。 */
  private static final String BUILTIN_TOOL = "read_file";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  @Autowired private ToolRegistry registry;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-mcp-broken");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("mcp-agent"));
      Files.writeString(
          root.resolve("mcp_servers.yaml"),
          """
          servers:
            - name: %s
              transport: stdio
              command: /nonexistent/oryxos-mcp-fixture-does-not-exist
          """
              .formatted(SERVER_NAME));
      Files.writeString(
          root.resolve("agents/mcp-agent/AGENT.md"),
          """
          ---
          name: mcp-agent
          description: MCP 自测 Agent
          identity:
            agent_name: MCP小欧
            prompt: 你是一个测试助手。
          provider:
            name: mock
            model: mock-model
          tools:
            - %s
          settings:
            max_iterations: 10
            max_history_turns: 20
          ---
          你是一个测试助手，被触发时正常回应。
          """
              .formatted(TOOL_NAME));
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("mcp-broken.db"));
  }

  @Test
  @DisplayName("MCP server 拉不起来时应用照常启动，内置工具还在，该 MCP 工具不在")
  void aBrokenMcpServerDoesNotTakeTheApplicationDown() throws Exception {
    // ① 应用起来了（上下文能到这里本身就是证据，再确认它真的在响应）
    var health = rest.getForEntity("/api/v1/health", String.class);
    assertThat(health.getStatusCode().is2xxSuccessful()).as("配错的 MCP server 不应拖垮启动").isTrue();

    // ② 该 MCP server 的工具没有进注册表，也没有归属记录
    assertThat(registry.mcpToolOwners())
        .as("连不上的 server 不应留下工具归属")
        .doesNotContainValue(SERVER_NAME);

    List<JsonNode> tools = valuesOf(getData("/api/v1/tools"));
    assertThat(tools)
        .as("连不上的 server 的工具不应出现在工具列表里")
        .noneMatch(tool -> TOOL_NAME.equals(tool.path("name").asText()));

    // ③ 内置工具不受影响（证明是"这一个 server 失败"，不是"工具面整体挂了"）
    assertThat(tools)
        .as("内置工具应照常可用")
        .anyMatch(tool -> BUILTIN_TOOL.equals(tool.path("name").asText()));
  }

  /** 统一信封里的 {@code data}。 */
  private JsonNode getData(String path) throws Exception {
    var response = rest.getForEntity(path, String.class);
    assertThat(response.getStatusCode().value()).as("HTTP 应 200").isEqualTo(200);
    var body = mapper.readTree(response.getBody());
    assertThat(body.get("code").asInt()).as("统一信封 code 应为 0").isZero();
    return body.get("data");
  }

  private static List<JsonNode> valuesOf(JsonNode array) {
    List<JsonNode> values = new ArrayList<>();
    array.forEach(values::add);
    return values;
  }
}
