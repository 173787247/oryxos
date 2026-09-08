package io.oryxos.core.channel;

import java.util.Map;

/**
 * 入站 webhook 原始请求。
 *
 * @param method HTTP 方法（GET/POST）
 * @param query 查询参数（WhatsApp 订阅挑战用）
 * @param headers 小写 header 名
 * @param body 原始 body；GET 时为空串
 */
public record WebhookRequest(
    String method, Map<String, String> query, Map<String, String> headers, String body) {

  public WebhookRequest {
    method = method == null ? "" : method;
    query = query == null ? Map.of() : Map.copyOf(query);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    body = body == null ? "" : body;
  }

  public String header(String name) {
    if (name == null) {
      return null;
    }
    return headers.get(name.toLowerCase(java.util.Locale.ROOT));
  }

  public String query(String name) {
    return query.get(name);
  }
}
