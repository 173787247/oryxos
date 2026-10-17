package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * MCP 的启动级验证：工作区里配置一个 MCP server → 应用启动 → 工具进注册表 → 真的调通。
 *
 * <p>为什么需要它：全仓 {@code oryxos.mcp*} 在测试目录里只有一处引用（还是个配置文件守卫）。{@code McpClientService} / {@code
 * McpConfigLoader} / {@code McpToolAdapter} 五六个类只在单元层面被 mock 过 —— **把配置读出来、真的拉起一个 MCP 进程、握手、把工具挂进
 * ToolRegistry** 这条链没有任何启动级测试，而 MCP 是本项目 "零代码接入"的头号扩展能力：它坏了 CI 只会静默。
 *
 * <p>MCP server 用的是与 {@code McpTransportCompatibilityTest} 同款的协议 fixture（一个说 JSON-RPC 的 stdio
 * 脚本，只支持 initialize / tools/list / tools/call）。只有 fixture 是假的：配置加载、进程拉起、SDK 握手、 工具适配与注册、调用往返全走真实路径。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class McpServerBootE2ETest {

  private static final Path ROOT = seedWorkspace();

  private static final String SERVER_NAME = "fixture";

  private static final String TOOL_NAME = "echo";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  @Autowired private ToolRegistry registry;

  /**
   * 临时工作区：一份 {@code mcp_servers.yaml}（stdio 指向 fixture 脚本）+ 一个把 MCP 工具列进白名单的 Agent。
   *
   * <p>脚本用 {@code python3 <path>} 拉起，所以不需要可执行位。
   */
  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-mcp-boot");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("mcp-agent"));

      Path server = root.resolve("mcp-fixture.py");
      Files.writeString(server, stdioFixture());

      Files.writeString(
          root.resolve("mcp_servers.yaml"),
          """
          servers:
            - name: %s
              transport: stdio
              command: python3 %s
          """
              .formatted(SERVER_NAME, server));

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

  private static String stdioFixture() {
    return """
        #!/usr/bin/env python3
        import json, sys

        for line in sys.stdin:
            request = json.loads(line)
            request_id = request.get("id")
            if request_id is None:
                continue
            method = request.get("method")
            if method == "initialize":
                result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                          "serverInfo": {"name": "stdio-fixture", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": [{"name": "echo", "description": "echo back",
                                      "inputSchema": {"type": "object"}}]}
            elif method == "tools/call":
                value = request["params"]["arguments"]["value"]
                result = {"content": [{"type": "text", "text": "reply:" + value}],
                          "isError": False}
            else:
                result = {}
            print(json.dumps({"jsonrpc": "2.0", "id": request_id, "result": result}), flush=True)
        """;
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("mcp-boot.db"));
  }

  @Test
  @DisplayName("启动即连上 MCP server：工具出现在 /api/v1/tools 并归属该 server")
  void mcpToolsAreRegisteredAtStartup() throws Exception {
    java.util.List<com.fasterxml.jackson.databind.JsonNode> tools =
        valuesOf(getData("/api/v1/tools"));

    assertThat(tools)
        .as("工具列表应含 MCP fixture 暴露的 %s", TOOL_NAME)
        .anyMatch(tool -> TOOL_NAME.equals(tool.path("name").asText()));

    // 归属信息证明它来自 MCP server，而不是同名的内置工具
    assertThat(registry.mcpToolOwners())
        .as("注册表应记录该工具的 MCP 归属")
        .containsEntry(TOOL_NAME, SERVER_NAME);
  }

  @Test
  @DisplayName("注册进来的 MCP 工具能真的调通（走真实 stdio 传输）")
  void mcpToolRoundTripsThroughTheRealTransport() throws Exception {
    assertThat(registry.contains(TOOL_NAME)).as("工具应已注册").isTrue();

    ToolResult result =
        registry.get(TOOL_NAME).orElseThrow().execute(mapper.readTree("{\"value\":\"hi\"}"));

    assertThat(result.success()).as("MCP 调用应成功：%s", result.errorMessage()).isTrue();
    assertThat(result.content()).isEqualTo("reply:hi");
  }

  /** 统一信封里的 {@code data}。 */
  private com.fasterxml.jackson.databind.JsonNode getData(String path) throws Exception {
    var response = rest.getForEntity(path, String.class);
    assertThat(response.getStatusCode().value()).as("HTTP 应 200").isEqualTo(200);
    var body = mapper.readTree(response.getBody());
    assertThat(body.get("code").asInt()).as("统一信封 code 应为 0").isZero();
    return body.get("data");
  }

  private static java.util.List<com.fasterxml.jackson.databind.JsonNode> valuesOf(
      com.fasterxml.jackson.databind.JsonNode array) {
    java.util.List<com.fasterxml.jackson.databind.JsonNode> values = new java.util.ArrayList<>();
    array.forEach(values::add);
    return values;
  }
}
