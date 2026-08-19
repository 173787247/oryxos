package io.oryxos.web.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.storage.Session;
import io.oryxos.storage.SessionRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.controller.dto.KnowledgeMetricsView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T042：看板聚合只消费审计数据（FR-023 / SC-009）——指标可与同窗口的审计行手工核对一致。 */
class KnowledgeMetricsServiceTest {

  private ToolInvocationRepository invocations;
  private SessionRepository sessions;
  private KnowledgeMetricsService service;

  private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-31T00:00:00Z");

  @BeforeEach
  void setUp() {
    invocations = mock(ToolInvocationRepository.class);
    sessions = mock(SessionRepository.class);
    service = new KnowledgeMetricsService(invocations, sessions);
  }

  @Test
  @DisplayName("命中/零结果/降级/文档分布/出处引用率——逐项与审计行对得上")
  void aggregatesAttributedRows() {
    ToolInvocation hitRow =
        row(
            "s1",
            "{\"query\":\"磁盘告警\"}",
            payload(
                "磁盘告警",
                false,
                false,
                "{\"kb\":\"ops\",\"path\":\"disk.md\",\"position\":\"1\"}",
                "{\"kb\":\"ops\",\"path\":\"disk.md\",\"position\":\"2\"}"));
    ToolInvocation degradedRow =
        row(
            "s2",
            "{\"query\":\"网络\"}",
            payload("网络", false, true, "{\"kb\":\"ops\",\"path\":\"net.md\",\"position\":\"1\"}"));
    ToolInvocation zeroWithFilter =
        row("s3", "{\"query\":\"没有的内容\",\"knowledgeBase\":\"ops\"}", payload("没有的内容", true, false));
    ToolInvocation zeroUnattributed =
        row("s4", "{\"query\":\"跨库无命中\"}", payload("跨库无命中", true, false));
    ToolInvocation otherKb =
        row(
            "s5",
            "{\"query\":\"别的库\"}",
            payload("别的库", false, false, "{\"kb\":\"faq\",\"path\":\"q.md\",\"position\":\"1\"}"));
    ToolInvocation scopeError = row("s6", "{\"query\":\"x\"}", "\"检索失败: 未绑定\"");
    when(invocations.findByToolNameAndCreatedAtBetweenOrderByIdDesc(
            eq("retrieve_knowledge"), any(), any()))
        .thenReturn(
            List.of(hitRow, degradedRow, zeroWithFilter, zeroUnattributed, otherKb, scopeError));
    // s1 的会话回答里出现了出处路径 → 被引用；s2 的没有 → 未引用
    when(sessions.findById("s1"))
        .thenReturn(
            Optional.of(session("[{\"role\":\"assistant\",\"content\":\"见 disk.md 第1段\"}]")));
    when(sessions.findById("s2"))
        .thenReturn(Optional.of(session("[{\"role\":\"assistant\",\"content\":\"未给出处\"}]")));

    KnowledgeMetricsView view = service.compute("ops", FROM, TO);

    assertEquals(3, view.retrievalCount(), "归属 ops 的检索：hit + degraded + 限定库零结果");
    assertEquals(1, view.zeroResultCount());
    assertEquals(List.of("没有的内容"), view.zeroResultQueries());
    assertEquals(1, view.degradedCount());
    assertEquals(1.0 / 3, view.zeroResultRate(), 1e-9);
    // 文档分布：disk.md 命中 2 片、net.md 1 片，降序
    assertEquals("disk.md", view.hitDocuments().get(0).relPath());
    assertEquals(2, view.hitDocuments().get(0).hits());
    assertEquals("net.md", view.hitDocuments().get(1).relPath());
    // 出处引用率：s1 引用 / (s1 + s2) = 0.5
    assertEquals(0.5, view.citationRate(), 1e-9);
    // 未限定库的零结果单列呈现，不隐藏也不归属
    assertEquals(1, view.unattributedZeroResults());
    assertEquals(List.of("跨库无命中"), view.unattributedZeroResultQueries());
  }

  @Test
  @DisplayName("窗口内无归属数据：计数 0、比率为 null（不造伪指标）")
  void emptyWindowYieldsNullsNotFakeRates() {
    when(invocations.findByToolNameAndCreatedAtBetweenOrderByIdDesc(
            eq("retrieve_knowledge"), any(), any()))
        .thenReturn(List.of());

    KnowledgeMetricsView view = service.compute("ops", FROM, TO);

    assertEquals(0, view.retrievalCount());
    assertNull(view.zeroResultRate());
    assertNull(view.degradedRate());
    assertNull(view.citationRate());
  }

  /** 埋点结构（contracts §3）双层编码：result_json 是工具字符串结果的 JSON 编码。 */
  private static String payload(
      String query, boolean zeroResult, boolean degraded, String... hits) {
    String inner =
        "{\"query\":\""
            + query
            + "\",\"hits\":["
            + String.join(",", hits)
            + "],\"zero_result\":"
            + zeroResult
            + ",\"degraded\":"
            + degraded
            + ",\"duration_ms\":5}";
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inner);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ToolInvocation row(String sessionId, String inputJson, String resultJson) {
    ToolInvocation row = new ToolInvocation();
    row.setSessionId(sessionId);
    row.setToolName("retrieve_knowledge");
    row.setInputJson(inputJson);
    row.setResultJson(resultJson);
    row.setSuccess(true);
    return row;
  }

  private static Session session(String messagesJson) {
    Session session = new Session();
    session.setMessagesJson(messagesJson);
    return session;
  }
}
