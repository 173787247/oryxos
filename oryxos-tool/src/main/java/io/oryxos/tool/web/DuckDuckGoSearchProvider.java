package io.oryxos.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.PinnedHttpReadClient;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * 核心阶段唯一搜索实现：DuckDuckGo Instant Answer API（无需 API key，返回 JSON）。
 *
 * <p>先抓 RelatedTopics / Abstract；若仍为空（中文与时效查询常见），再请求 HTML 轻量结果页并解析标题链接——同样过 {@code HTTP_READ}
 * 且禁自动重定向。测试构造可关掉 HTML 兜底，只打假 Instant Answer 端点。
 */
public class DuckDuckGoSearchProvider implements SearchProvider {

  private static final String DEFAULT_ENDPOINT = "https://api.duckduckgo.com/";
  private static final String DEFAULT_HTML_ENDPOINT = "https://html.duckduckgo.com/html/";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
  private static final int MAX_HTML_RESULTS = 8;
  private static final int TITLE_SNIPPET_CAP = 60;
  private static final Pattern HTML_RESULT_LINK =
      Pattern.compile(
          "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern TAG_STRIP = Pattern.compile("<[^>]+>");
  private static final Pattern UDDG = Pattern.compile("[?&]uddg=([^&]+)");

  private final String endpoint;
  private final String htmlFallbackEndpoint;
  private final Sandbox sandbox;

  public DuckDuckGoSearchProvider(RestClient restClient, Sandbox sandbox) {
    this(restClient, DEFAULT_ENDPOINT, DEFAULT_HTML_ENDPOINT, sandbox);
  }

  /** 单测用：只打 Instant Answer 端点，关闭 HTML 兜底。 */
  public DuckDuckGoSearchProvider(RestClient restClient, String endpoint, Sandbox sandbox) {
    this(restClient, endpoint, null, sandbox);
  }

  public DuckDuckGoSearchProvider(
      RestClient restClient, String endpoint, String htmlFallbackEndpoint, Sandbox sandbox) {
    Objects.requireNonNull(restClient, "restClient 不能为空"); // 保留签名，供 Spring 装配
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint 不能为空");
    this.htmlFallbackEndpoint = htmlFallbackEndpoint;
  }

  @Override
  public List<SearchResult> search(String query) {
    List<SearchResult> instant = searchInstantAnswer(query);
    if (!instant.isEmpty() || htmlFallbackEndpoint == null || htmlFallbackEndpoint.isBlank()) {
      return instant;
    }
    return searchHtmlLite(query);
  }

  private List<SearchResult> searchInstantAnswer(String query) {
    sandbox.enforce(new SandboxAction(ActionType.HTTP_READ, endpoint));
    ResponseEntity<String> resp;
    try (PinnedHttpReadClient client =
        PinnedHttpReadClient.open(sandbox, CONNECT_TIMEOUT, READ_TIMEOUT)) {
      resp =
          client
              .restClient()
              .get()
              .uri(endpoint + "?q={q}&format=json&no_html=1", query)
              .retrieve()
              .toEntity(String.class);
    }
    if (resp.getStatusCode().is3xxRedirection()) {
      throw new IllegalStateException("搜索端点返回重定向，拒绝跟随: " + endpoint);
    }
    return parseInstantAnswer(resp.getBody());
  }

  private List<SearchResult> searchHtmlLite(String query) {
    sandbox.enforce(new SandboxAction(ActionType.HTTP_READ, htmlFallbackEndpoint));
    ResponseEntity<String> resp;
    try (PinnedHttpReadClient client =
        PinnedHttpReadClient.open(sandbox, CONNECT_TIMEOUT, READ_TIMEOUT)) {
      resp =
          client
              .restClient()
              .get()
              .uri(htmlFallbackEndpoint + "?q={q}", query)
              .retrieve()
              .toEntity(String.class);
    }
    if (resp.getStatusCode().is3xxRedirection()) {
      throw new IllegalStateException("搜索 HTML 端点返回重定向，拒绝跟随: " + htmlFallbackEndpoint);
    }
    return parseHtmlResults(resp.getBody());
  }

  static List<SearchResult> parseInstantAnswer(String body) {
    List<SearchResult> results = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return results;
    }
    try {
      JsonNode root = MAPPER.readTree(body);
      collectTopics(root.path("RelatedTopics"), results);
      if (results.isEmpty()) {
        String abstractText = textOrEmpty(root.get("AbstractText"));
        if (abstractText.isBlank()) {
          abstractText = textOrEmpty(root.get("Abstract"));
        }
        String url = textOrEmpty(root.get("AbstractURL"));
        String heading = textOrEmpty(root.get("Heading"));
        if (!abstractText.isBlank() && !url.isBlank()) {
          String title = heading.isBlank() ? clip(abstractText, TITLE_SNIPPET_CAP) : heading;
          results.add(new SearchResult(title, url, abstractText));
        }
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("搜索结果解析失败", e);
    }
    return results;
  }

  private static void collectTopics(JsonNode topics, List<SearchResult> results) {
    if (topics == null || !topics.isArray()) {
      return;
    }
    for (JsonNode topic : topics) {
      JsonNode nested = topic.get("Topics");
      if (nested != null && nested.isArray()) {
        collectTopics(nested, results);
        continue;
      }
      JsonNode text = topic.get("Text");
      JsonNode url = topic.get("FirstURL");
      if (text != null && url != null) {
        String snippet = text.asText();
        results.add(new SearchResult(clip(snippet, TITLE_SNIPPET_CAP), url.asText(), snippet));
      }
    }
  }

  static List<SearchResult> parseHtmlResults(String body) {
    List<SearchResult> results = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return results;
    }
    Matcher matcher = HTML_RESULT_LINK.matcher(body);
    while (matcher.find() && results.size() < MAX_HTML_RESULTS) {
      String href = unwrapUddg(matcher.group(1));
      String title = TAG_STRIP.matcher(matcher.group(2)).replaceAll("").strip();
      if (href.isBlank() || title.isBlank()) {
        continue;
      }
      results.add(new SearchResult(title, href, title));
    }
    return results;
  }

  private static String unwrapUddg(String href) {
    if (href == null || href.isBlank()) {
      return "";
    }
    Matcher m = UDDG.matcher(href);
    if (m.find()) {
      return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }
    return href.strip();
  }

  private static String textOrEmpty(JsonNode node) {
    return node == null || node.isNull() ? "" : node.asText("").strip();
  }

  private static String clip(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }
}
