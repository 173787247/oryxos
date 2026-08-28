package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigLoaderTest {

  @TempDir Path dir;

  private Path configFile() {
    return dir.resolve("mcp_servers.yaml");
  }

  private void write(String yaml) throws IOException {
    Files.writeString(configFile(), yaml);
  }

  @Test
  @DisplayName("文件缺失时 loadRaw 返回空列表")
  void missingFileReturnsEmpty() {
    assertEquals(0, new McpConfigLoader(configFile()).loadRaw().size());
  }

  @Test
  @DisplayName("YAML 解析失败时 loadRaw 抛 IllegalArgumentException")
  void malformedYamlFailsLoud() throws IOException {
    write("servers:\n  - name: [unclosed\n");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());

    assertTrue(ex.getMessage().contains("mcp_servers.yaml 解析失败"));
  }

  @Test
  @DisplayName("顶层 servers 非列表时 loadRaw 抛 IllegalArgumentException")
  void serversNotListFailsLoud() throws IOException {
    write("servers: not-a-list\n");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());

    assertTrue(ex.getMessage().contains("servers 必须是列表"));
  }

  @Test
  @DisplayName("servers 条目非对象时 loadRaw 抛 IllegalArgumentException")
  void serversEntryNotMapFailsLoud() throws IOException {
    write("servers:\n  - plain-string\n");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());

    assertTrue(ex.getMessage().contains("非对象条目"));
  }

  @Test
  @DisplayName("YAML 1.1 布尔词 yes 不得被 String.valueOf 改成 name=true")
  void rejectsYamlBooleanWordAsName() throws Exception {
    write(
        """
        servers:
          - name: yes
            transport: stdio
            command: echo
        """);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());
    assertTrue(e.getMessage().contains("字符串") || e.getMessage().contains("Boolean"));

    write(
        """
        servers:
          - name: "yes"
            transport: stdio
            command: echo
        """);
    List<io.oryxos.core.mcp.McpServerConfig> ok = new McpConfigLoader(configFile()).loadRaw();
    assertEquals(1, ok.size());
    assertEquals("yes", ok.get(0).name());
  }
}
