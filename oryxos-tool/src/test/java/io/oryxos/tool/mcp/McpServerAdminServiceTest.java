package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** 31 节验收：MCP server 管理台 CRUD——增/改/删都是"落盘 + 立即生效"，凭证占位符不因编辑而被解析后写死。 */
class McpServerAdminServiceTest {

  @TempDir Path dir;

  private McpServerAdminService serviceWith(
      java.util.function.Function<McpServerConfig, io.modelcontextprotocol.client.McpSyncClient>
          factory) {
    McpConfigLoader loader = new McpConfigLoader(dir.resolve("mcp_servers.yaml"));
    McpClientService clientService = new McpClientService(loader, factory);
    return new McpServerAdminService(loader, clientService, new ToolRegistry());
  }

  private static io.modelcontextprotocol.client.McpSyncClient goodClient() {
    io.modelcontextprotocol.client.McpSyncClient client =
        org.mockito.Mockito.mock(io.modelcontextprotocol.client.McpSyncClient.class);
    org.mockito.Mockito.when(client.listTools())
        .thenReturn(
            new io.modelcontextprotocol.spec.McpSchema.ListToolsResult(
                List.of(
                    io.modelcontextprotocol.spec.McpSchema.Tool.builder()
                        .name("demo_tool")
                        .description("demo")
                        .inputSchema(io.modelcontextprotocol.json.McpJsonDefaults.getMapper(), "{}")
                        .build()),
                null));
    return client;
  }

  @Test
  @DisplayName("add: 落盘 + 立即连接")
  void add_persistsAndConnects() {
    McpServerAdminService service = serviceWith(c -> goodClient());

    service.add(new McpServerConfig("demo", "stdio", "echo hi", Map.of(), null, Map.of()));

    assertEquals(1, service.list().size());
    List<McpServerStatus> statuses = service.status();
    assertTrue(statuses.get(0).connected());
    assertTrue(statuses.get(0).toolNames().contains("demo_tool"));
  }

  @Test
  @DisplayName("add: 名字冲突拒绝")
  void add_duplicateName_rejected() {
    McpServerAdminService service = serviceWith(c -> goodClient());
    service.add(new McpServerConfig("demo", "stdio", "echo hi", Map.of(), null, Map.of()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.add(
                new McpServerConfig("demo", "stdio", "echo hi2", Map.of(), null, Map.of())));
  }

  @Test
  @DisplayName("remove: 断开连接 + 从配置移除，幂等")
  void remove_disconnectsAndPersists() {
    McpServerAdminService service = serviceWith(c -> goodClient());
    service.add(new McpServerConfig("demo", "stdio", "echo hi", Map.of(), null, Map.of()));

    service.remove("demo");

    assertTrue(service.list().isEmpty());
    assertFalse(service.status().stream().anyMatch(s -> s.name().equals("demo") && s.connected()));
    assertTrue(
        () -> {
          service.remove("demo"); // 幂等：不存在也不抛
          return true;
        });
  }

  @Test
  @DisplayName("update: raw 配置里的 ${ENV} 占位符原样落盘，不因编辑被解析成明文")
  void update_keepsPlaceholderLiteralOnDisk() throws IOException {
    McpServerAdminService service = serviceWith(c -> goodClient());
    service.add(
        new McpServerConfig(
            "demo", "stdio", "echo hi", Map.of("TOKEN", "${MY_SECRET_ENV}"), null, Map.of()));

    service.update(
        "demo",
        new McpServerConfig(
            "demo", "stdio", "echo hi --v2", Map.of("TOKEN", "${MY_SECRET_ENV}"), null, Map.of()));

    String yaml = Files.readString(dir.resolve("mcp_servers.yaml"));
    assertTrue(yaml.contains("${MY_SECRET_ENV}"), "占位符必须原样落盘，不能被解析成真实凭证明文");
  }

  @Test
  @DisplayName("catalog: 返回内置目录，非空")
  void catalog_returnsBuiltInEntries() {
    McpServerAdminService service = serviceWith(c -> goodClient());
    assertFalse(service.catalog().isEmpty());
  }

  @Test
  @DisplayName("auto 配置经管理服务和 YAML 往返保留 URL、headers 与 timeout")
  void auto_persistsUpdatesAndConnects() {
    McpServerAdminService service = serviceWith(c -> goodClient());
    McpServerConfig config =
        new McpServerConfig(
            "remote",
            "auto",
            null,
            Map.of(),
            "https://example.invalid/team/mcp?x=a%2Fb",
            Map.of("Authorization", "Bearer ${MCP_TOKEN}"),
            120);
    assertDoesNotThrow(() -> service.add(config));
    assertEquals(config, service.list().get(0));
    assertTrue(service.status().get(0).connected());
    McpServerConfig changed =
        new McpServerConfig(
            "remote",
            "auto",
            null,
            Map.of(),
            "https://example.invalid/team/events?x=c%2Fd",
            config.headers(),
            240);
    assertDoesNotThrow(() -> service.update("remote", changed));
    assertEquals(changed, service.list().get(0));
    assertTrue(service.status().get(0).connected());
  }

  /** 写盘失败：只读卷 / 磁盘满 / 权限变更——真实的 {@link McpConfigLoader#save} 抛 UncheckedIOException。 */
  private static final class FailingSaveLoader extends McpConfigLoader {

    FailingSaveLoader(Path file) {
      super(file);
    }

    @Override
    public void save(List<McpServerConfig> configs) {
      throw new UncheckedIOException(new IOException("只读卷: Read-only file system"));
    }
  }

  /** 一条写进文件、已连上的 server。返回 admin 与运行态供断言。 */
  private record RunningServer(
      McpServerAdminService admin,
      McpClientService clientService,
      ToolRegistry registry,
      io.modelcontextprotocol.client.McpSyncClient client) {}

  private RunningServer runningServerWithFailingSave() throws IOException {
    Files.writeString(
        dir.resolve("mcp_servers.yaml"),
        """
        servers:
          - name: demo
            transport: stdio
            command: echo hi
        """);
    var client = goodClient();
    McpConfigLoader loader = new FailingSaveLoader(dir.resolve("mcp_servers.yaml"));
    McpClientService clientService = new McpClientService(loader, c -> client);
    ToolRegistry registry = new ToolRegistry();
    clientService.connectAll(registry);
    McpServerAdminService admin = new McpServerAdminService(loader, clientService, registry);
    assertTrue(registry.contains("demo_tool"), "前置条件：连接已注册工具");
    return new RunningServer(admin, clientService, registry, client);
  }

  @Test
  @DisplayName("update: 落盘失败时不得先把旧连接拆掉（否则运行态没了、磁盘仍写着在用）")
  void update_saveFailureKeepsTheRunningConnection() throws IOException {
    RunningServer running = runningServerWithFailingSave();

    assertThrows(
        UncheckedIOException.class,
        () ->
            running
                .admin()
                .update(
                    "demo",
                    new McpServerConfig(
                        "demo", "stdio", "echo hi --v2", Map.of(), null, Map.of())));

    assertTrue(running.registry().contains("demo_tool"), "落盘失败不应注销已注册的工具");
    assertTrue(running.clientService().status("demo").connected(), "落盘失败不应断开旧连接");
    assertTrue(running.admin().list().get(0).command().contains("echo hi"));
    Mockito.verify(running.client(), Mockito.never()).closeGracefully();
  }

  @Test
  @DisplayName("remove: 落盘失败时不得先把连接拆掉（否则磁盘仍列着它、运行态却没了）")
  void remove_saveFailureKeepsTheRunningConnection() throws IOException {
    RunningServer running = runningServerWithFailingSave();

    assertThrows(UncheckedIOException.class, () -> running.admin().remove("demo"));

    assertEquals(1, running.admin().list().size(), "落盘失败，文件仍应列着这条 server");
    assertTrue(running.registry().contains("demo_tool"), "落盘失败不应注销已注册的工具");
    assertTrue(running.clientService().status("demo").connected(), "落盘失败不应断开连接");
    Mockito.verify(running.client(), Mockito.never()).closeGracefully();
  }
}
