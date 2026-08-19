package io.oryxos.web.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.Session;
import io.oryxos.storage.SessionRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.controller.dto.KnowledgeMetricsView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 看板聚合（FR-023）：只读 {@code tool_invocations} 里 retrieve_knowledge 的埋点（FR-022 的结构化
 * 结果），按知识库归属聚合——命中含本库或调用显式限定本库即归属；未限定单库的零结果无法归属到 具体库，单列呈现不隐藏（管理员仍能看到「有查询没命中」）。出处引用率关联会话回答文本近似计算。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "repository 为 Spring 注入的共享单例，构造注入存同一引用正是意图。")
@org.springframework.stereotype.Service
public class KnowledgeMetricsService {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeMetricsService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TOOL_NAME = "retrieve_knowledge";

  /** 埋点结构里的命中数组字段名（contracts/knowledge-spi.md §3）。 */
  private static final String HITS_FIELD = "hits";

  private final ToolInvocationRepository invocations;
  private final SessionRepository sessions;

  public KnowledgeMetricsService(ToolInvocationRepository invocations, SessionRepository sessions) {
    this.invocations = invocations;
    this.sessions = sessions;
  }

  public KnowledgeMetricsView compute(String kbName, Instant from, Instant to) {
    List<ToolInvocation> rows =
        invocations.findByToolNameAndCreatedAtBetweenOrderByIdDesc(TOOL_NAME, from, to);
    long retrievalCount = 0;
    long zeroResultCount = 0;
    long degradedCount = 0;
    long citedCount = 0;
    long citationDenominator = 0;
    long unattributedZeroResults = 0;
    List<String> zeroResultQueries = new ArrayList<>();
    List<String> unattributedQueries = new ArrayList<>();
    Map<String, Long> hitDocuments = new LinkedHashMap<>();
    for (ToolInvocation row : rows) {
      JsonNode payload = parsePayload(row.getResultJson());
      if (payload == null) {
        continue; // 范围错误等非结构化结果：不属于任何库的检索
      }
      String kbFilter = inputKbFilter(row.getInputJson());
      List<String> hitPaths = hitPathsOf(payload, kbName);
      boolean zeroResult = payload.path("zero_result").asBoolean(false);
      boolean attributed = !hitPaths.isEmpty() || kbName.equals(kbFilter);
      if (zeroResult && kbFilter == null) {
        unattributedZeroResults++;
        addQuery(unattributedQueries, payload);
        continue;
      }
      if (!attributed) {
        continue;
      }
      retrievalCount++;
      if (zeroResult) {
        zeroResultCount++;
        addQuery(zeroResultQueries, payload);
      }
      if (payload.path("degraded").asBoolean(false)) {
        degradedCount++;
      }
      hitPaths.forEach(path -> hitDocuments.merge(path, 1L, Long::sum));
      if (!hitPaths.isEmpty()) {
        int cited = citedInSession(row.getSessionId(), hitPaths);
        if (cited >= 0) {
          citationDenominator++;
          citedCount += cited;
        }
      }
    }
    List<KnowledgeMetricsView.DocumentHits> distribution =
        hitDocuments.entrySet().stream()
            .map(entry -> new KnowledgeMetricsView.DocumentHits(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingLong(KnowledgeMetricsView.DocumentHits::hits).reversed())
            .toList();
    return new KnowledgeMetricsView(
        from,
        to,
        retrievalCount,
        zeroResultCount,
        rate(zeroResultCount, retrievalCount),
        degradedCount,
        rate(degradedCount, retrievalCount),
        rate(citedCount, citationDenominator),
        distribution,
        zeroResultQueries,
        unattributedZeroResults,
        unattributedQueries);
  }

  /** result_json 是工具字符串结果的 JSON 编码（双层）：先解外层字符串，再解内层结构化埋点。 */
  static JsonNode parsePayload(String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      JsonNode outer = MAPPER.readTree(resultJson);
      JsonNode inner = outer.isTextual() ? MAPPER.readTree(outer.asText()) : outer;
      return inner.isObject() && inner.has(HITS_FIELD) ? inner : null;
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      return null; // 可读错误文本（如「检索失败: …」）不是结构化埋点
    }
  }

  private static String inputKbFilter(String inputJson) {
    if (inputJson == null || inputJson.isBlank()) {
      return null;
    }
    try {
      JsonNode input = MAPPER.readTree(inputJson);
      String kb = input.path("knowledgeBase").asText(input.path("knowledge_base").asText(""));
      return kb.isBlank() ? null : kb;
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      return null;
    }
  }

  /** 归属本库的命中文档相对路径列表。 */
  private static List<String> hitPathsOf(JsonNode payload, String kbName) {
    List<String> paths = new ArrayList<>();
    for (JsonNode hit : payload.path(HITS_FIELD)) {
      if (kbName.equals(hit.path("kb").asText()) && !hit.path("path").asText().isBlank()) {
        paths.add(hit.path("path").asText());
      }
    }
    return paths;
  }

  private static void addQuery(List<String> queries, JsonNode payload) {
    String query = payload.path("query").asText("");
    if (!query.isBlank()) {
      queries.add(query);
    }
  }

  /** 出处引用率的近似判定：任一命中路径出现在该会话的助手回答文本里即视为被引用。 返回 1=被引用、0=未引用、-1=无会话（无状态调用等），-1 不进分母。 */
  private int citedInSession(String sessionId, List<String> hitPaths) {
    if (sessionId == null || sessionId.isBlank()) {
      return -1;
    }
    Session session = sessions.findById(sessionId).orElse(null);
    if (session == null || session.getMessagesJson() == null) {
      return -1;
    }
    try {
      for (JsonNode message : MAPPER.readTree(session.getMessagesJson())) {
        if (!"assistant".equals(message.path("role").asText())) {
          continue;
        }
        String content = message.path("content").asText("");
        for (String path : hitPaths) {
          if (content.contains(path)) {
            return 1;
          }
        }
      }
      return 0;
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      LOG.warn("解析会话历史失败，出处引用率跳过该会话: {}", sessionId.replace('\r', '_').replace('\n', '_'));
      return -1;
    }
  }

  private static Double rate(long numerator, long denominator) {
    return denominator == 0 ? null : (double) numerator / denominator;
  }
}
