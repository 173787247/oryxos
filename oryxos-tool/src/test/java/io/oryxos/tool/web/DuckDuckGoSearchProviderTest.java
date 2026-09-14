package io.oryxos.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Instant Answer 空结果时的 Abstract / HTML 兜底（假 HTTP，不碰真网）。 */
class DuckDuckGoSearchProviderTest {

  private HttpServer instant;
  private HttpServer html;

  @AfterEach
  void stop() {
    if (instant != null) {
      instant.stop(0);
    }
    if (html != null) {
      html.stop(0);
    }
  }

  @Test
  @DisplayName("RelatedTopics 空时用 Abstract/AbstractURL")
  void parseAbstractWhenTopicsEmpty() {
    List<SearchProvider.SearchResult> results =
        DuckDuckGoSearchProvider.parseInstantAnswer(
            "{\"RelatedTopics\":[],\"Heading\":\"北京\","
                + "\"AbstractText\":\"首都\",\"AbstractURL\":\"https://example.com/beijing\"}");
    assertEquals(1, results.size());
    assertEquals("北京", results.get(0).title());
    assertEquals("https://example.com/beijing", results.get(0).url());
  }

  @Test
  @DisplayName("Instant Answer 空时跟 HTML lite 兜底")
  void emptyInstantFallsBackToHtml() throws IOException {
    AtomicInteger htmlHits = new AtomicInteger();
    instant = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    html = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    instant.createContext(
        "/",
        exchange -> {
          byte[] body = "{\"RelatedTopics\":[]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    html.createContext(
        "/",
        exchange -> {
          htmlHits.incrementAndGet();
          String page =
              "<a class=\"result__a\" href=\"https://weather.example/bj\">Beijing weather</a>";
          byte[] body = page.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    instant.start();
    html.start();

    String instantUrl = "http://127.0.0.1:" + instant.getAddress().getPort() + "/";
    String htmlUrl = "http://127.0.0.1:" + html.getAddress().getPort() + "/";
    Sandbox sandbox = new PermissiveSandbox();
    SearchProvider provider =
        new DuckDuckGoSearchProvider(RestClient.create(), instantUrl, htmlUrl, sandbox);

    List<SearchProvider.SearchResult> results = provider.search("beijing weather");
    assertEquals(1, htmlHits.get(), "should hit HTML fallback");
    assertEquals(1, results.size(), "results=" + results);
    assertEquals("Beijing weather", results.get(0).title());
    assertEquals("https://weather.example/bj", results.get(0).url());
  }

  @Test
  @DisplayName("解析 HTML 结果并解开 uddg 跳转")
  void parseHtmlUnwrapsUddg() {
    String page =
        "<a class=\"result__a\" href=\"https://duckduckgo.com/l/?uddg=https%3A%2F%2Fnews.example%2Fa\">"
            + "新闻标题</a>";
    List<SearchProvider.SearchResult> results = DuckDuckGoSearchProvider.parseHtmlResults(page);
    assertEquals(1, results.size());
    assertEquals("https://news.example/a", results.get(0).url());
    assertEquals("新闻标题", results.get(0).title());
  }
}
