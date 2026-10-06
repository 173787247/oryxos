package io.oryxos.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 目录条目是管理台「一键启用」的模板——transport 写错，用户照模板建出来的 server 就一步都用不了。 */
class McpCatalogTest {

  private static McpCatalogEntry entry(String id) {
    return McpCatalog.all().stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("目录里没有条目: " + id));
  }

  @Test
  @DisplayName("github 远程条目按官方文档用 streamable：http 在本仓是 SSE 别名，两者客户端不同")
  void githubRemoteEntryUsesStreamableTransport() {
    McpCatalogEntry github = entry("github");

    // GitHub 的远程 MCP server 是 Streamable HTTP（github-mcp-server 安装文档标题即
    // "Remote Server Setup (Streamable HTTP)"）。本仓 TRANSPORT_HTTP 走的是 SSE 客户端，
    // 写它就会拿 SSE 去连 Streamable 端点。
    assertEquals(McpServerConfig.TRANSPORT_STREAMABLE, github.transport());

    // 远程条目的形状：只给 url。写成 stdio 会生成一份 command/url 均为 null 的配置。
    assertTrue(
        McpServerConfig.isRemoteHttp(github.transport()),
        "远程条目必须落在 isRemoteHttp 里，否则一键启用会走 stdio 分支");
    assertNotNull(github.urlTemplate());
    assertNull(github.commandTemplate());
  }
}
