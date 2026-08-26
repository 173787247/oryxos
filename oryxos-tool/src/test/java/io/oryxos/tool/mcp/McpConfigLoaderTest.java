package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigLoaderTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("YAML 1.1 布尔词 yes 不得被 String.valueOf 改成 name=true")
  void rejectsYamlBooleanWordAsName() throws Exception {
    Path file = tempDir.resolve("mcp_servers.yaml");
    Files.writeString(
        file,
        """
        servers:
          - name: yes
            transport: stdio
            command: echo
        """);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> new McpConfigLoader(file).loadRaw());
    assertTrue(e.getMessage().contains("字符串") || e.getMessage().contains("Boolean"));

    Files.writeString(
        file,
        """
        servers:
          - name: "yes"
            transport: stdio
            command: echo
        """);
    List<io.oryxos.core.mcp.McpServerConfig> ok = new McpConfigLoader(file).loadRaw();
    assertEquals(1, ok.size());
    assertEquals("yes", ok.get(0).name());
  }
}
