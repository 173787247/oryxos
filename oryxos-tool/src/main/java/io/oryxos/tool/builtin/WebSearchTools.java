package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.web.SearchProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置网页搜索工具：web_search——日报/调研类 Agent 的第一入口。
 *
 * <p>搜索走网络，同 http_get 一样是只读涉外请求（HTTP_READ：默认放行 + 内网黑名单）。工具层用 {@code web_search:query}
 * 伪目标记账；具体引擎（{@link SearchProvider}）还须对<strong>真实 endpoint</strong>再过 HTTP_READ，且禁自动重定向（与 HttpTools
 * 读路径同策略）。本工具负责把结果渲染成模型好读的文本。
 */
public class WebSearchTools {

  private static final int MAX_RESULTS = 8;

  private final Sandbox sandbox;
  private final SearchProvider provider;

  public WebSearchTools(Sandbox sandbox, SearchProvider provider) {
    this.sandbox = sandbox;
    this.provider = provider;
  }

  @Tool(name = "web_search", description = "用搜索引擎检索网页，返回标题、链接和摘要列表")
  public String webSearch(@ToolParam(description = "搜索关键词") String query) {
    // 伪目标：无主机、供审计/策略标签；真实出网 SSRF 由 SearchProvider 对 endpoint 复检
    sandbox.enforce(new SandboxAction(ActionType.HTTP_READ, "web_search:" + query));
    List<SearchProvider.SearchResult> results = provider.search(query);
    if (results.isEmpty()) {
      return "（未搜到相关结果；可改用 fetch_webpage 打开公开搜索页，或 http_get 调公开 API）";
    }
    return results.stream()
        .limit(MAX_RESULTS)
        .map(r -> "- " + r.title() + "\n  " + r.url() + "\n  " + r.snippet())
        .collect(Collectors.joining("\n"));
  }
}
