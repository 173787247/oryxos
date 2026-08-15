package io.oryxos.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 核心阶段唯一搜索实现：DuckDuckGo Instant Answer API（无需 API key，返回 JSON）。
 *
 * <p>端点由构造传入（默认公网），便于测试指向假服务；抓取 RelatedTopics 里的 Text/FirstURL 作为结果条目。搜不到即空列表——由上层工具决定怎么表达"没搜到"。
 *
 * <p>出网策略与 {@code HttpTools} 读路径对齐：对<strong>真实 endpoint</strong>过 {@code HTTP_READ}（SSRF
 * 兜底），且<strong>禁自动重定向</strong>——Instant Answer 无需跟跳；避免共用 RestClient 默认跟随把公网入口 302 到内网/元数据。
 */
public class DuckDuckGoSearchProvider implements SearchProvider {

  private static final String DEFAULT_ENDPOINT = "https://api.duckduckgo.com/";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

  /** 禁自动重定向的专用客户端；不沿用注入 RestClient 的跟随策略。 */
  private final RestClient hopClient;

  private final String endpoint;
  private final Sandbox sandbox;

  public DuckDuckGoSearchProvider(RestClient restClient, Sandbox sandbox) {
    this(restClient, DEFAULT_ENDPOINT, sandbox);
  }

  public DuckDuckGoSearchProvider(RestClient restClient, String endpoint, Sandbox sandbox) {
    Objects.requireNonNull(restClient, "restClient 不能为空"); // 保留签名，供 Spring 装配
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint 不能为空");
    JdkClientHttpRequestFactory hopFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    hopFactory.setReadTimeout(READ_TIMEOUT);
    this.hopClient = RestClient.builder().requestFactory(hopFactory).build();
  }

  @Override
  public List<SearchResult> search(String query) {
    // 伪目标 web_search:query 只做工具层标签；真实出网 URL 必须再过 HTTP_READ / SSRF
    sandbox.enforce(new SandboxAction(ActionType.HTTP_READ, endpoint));
    ResponseEntity<String> resp =
        hopClient
            .get()
            .uri(endpoint + "?q={q}&format=json&no_html=1", query)
            .retrieve()
            .toEntity(String.class);
    if (resp.getStatusCode().is3xxRedirection()) {
      throw new IllegalStateException("搜索端点返回重定向，拒绝跟随: " + endpoint);
    }
    return parse(resp.getBody());
  }

  private static List<SearchResult> parse(String body) {
    List<SearchResult> results = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return results;
    }
    try {
      JsonNode root = MAPPER.readTree(body);
      JsonNode topics = root.path("RelatedTopics");
      for (JsonNode topic : topics) {
        JsonNode text = topic.get("Text");
        JsonNode url = topic.get("FirstURL");
        if (text != null && url != null) {
          String snippet = text.asText();
          String title = snippet.length() > 60 ? snippet.substring(0, 60) : snippet;
          results.add(new SearchResult(title, url.asText(), snippet));
        }
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("搜索结果解析失败", e);
    }
    return results;
  }
}
