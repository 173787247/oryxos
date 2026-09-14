package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import io.oryxos.tool.web.DuckDuckGoSearchProvider;
import io.oryxos.tool.web.SearchProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** web_search 工具 + DuckDuckGo provider 测试（假搜索服务，不碰真网）。 */
class WebSearchToolsTest {

  private HttpServer server;

  @BeforeEach
  void startFakeSearch() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          String json =
              "{\"RelatedTopics\":[{\"Text\":\"OryxOS 是企业级 Agent OS\","
                  + "\"FirstURL\":\"https://oryxos.example/intro\"}]}";
          byte[] body = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopFakeSearch() {
    server.stop(0);
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @Test
  @DisplayName("web_search 渲染标题/链接/摘要")
  void webSearchRendersResults() {
    Sandbox sandbox = new PermissiveSandbox();
    SearchProvider provider =
        new DuckDuckGoSearchProvider(RestClient.create(), endpoint(), sandbox);
    WebSearchTools tools = new WebSearchTools(sandbox, provider);

    String result = tools.webSearch("oryxos");

    assertTrue(result.contains("企业级 Agent OS"));
    assertTrue(result.contains("https://oryxos.example/intro"));
  }

  @Test
  @DisplayName("无结果时返回明确提示")
  void webSearchNoResult() {
    WebSearchTools tools = new WebSearchTools(new PermissiveSandbox(), query -> List.of());

    String result = tools.webSearch("zzz");
    assertTrue(result.contains("未搜到相关结果"));
    assertTrue(result.contains("fetch_webpage"));
  }

  @Test
  @DisplayName("越界会被拦：白名单拒绝时不发起搜索")
  void sandboxRejectionBlocksSearch() {
    Sandbox denying = mock(Sandbox.class);
    doThrow(new SandboxViolationException("搜索域名不在白名单")).when(denying).enforce(any());
    SearchProvider provider = mock(SearchProvider.class);
    WebSearchTools tools = new WebSearchTools(denying, provider);

    assertThrows(SandboxViolationException.class, () -> tools.webSearch("x"));
  }

  @Test
  @DisplayName("搜索端点 302 不跟随：sink 零命中（禁自动重定向）")
  void searchDoesNotFollowRedirect() throws IOException {
    HttpServer entry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger sinkHits = new AtomicInteger();
    try {
      sink.createContext(
          "/",
          exchange -> {
            sinkHits.incrementAndGet();
            byte[] body = "leaked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      String sinkUrl = "http://127.0.0.1:" + sink.getAddress().getPort() + "/sink";
      entry.createContext(
          "/",
          exchange -> {
            exchange.getResponseHeaders().add("Location", sinkUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      entry.start();
      sink.start();

      Sandbox sandbox = new PermissiveSandbox();
      String start = "http://127.0.0.1:" + entry.getAddress().getPort() + "/";
      // RestClient.create() 默认会跟随；provider 必须自建 NEVER，否则 sink 会被打到
      SearchProvider provider = new DuckDuckGoSearchProvider(RestClient.create(), start, sandbox);

      assertThrows(IllegalStateException.class, () -> provider.search("oryxos"));
      assertEquals(0, sinkHits.get(), "搜索端点 302 不得自动跟随到 sink");
    } finally {
      entry.stop(0);
      sink.stop(0);
    }
  }

  @Test
  @DisplayName("真实 endpoint 过 HTTP_READ：回环地址被 SSRF 拦下且零请求")
  void realEndpointSsrfBlocked() {
    AtomicInteger hits = new AtomicInteger();
    // 复用 @BeforeEach 的 server：WhitelistSandbox 对 127.0.0.1 走 SSRF 黑名单
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });

    Sandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of()));
    SearchProvider provider =
        new DuckDuckGoSearchProvider(RestClient.create(), endpoint(), whitelist);

    assertThrows(SandboxViolationException.class, () -> provider.search("x"));
    assertEquals(0, hits.get(), "endpoint SSRF 校验不过，请求不该发出");
  }
}
