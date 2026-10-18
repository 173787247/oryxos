package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.TraceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** 课件《第16节》验收 harness：LlmCallRepositoryTest——建表必须走手工 schema.sql。 */
@org.springframework.transaction.annotation.Transactional
abstract class LlmCallRepositoryContractTest {

  @Autowired LlmCallRepository repository;

  @Test
  void 手工建表脚本_成功记录写读完整() {
    LlmCall call = new LlmCall();
    call.setSessionId("s-1");
    call.setProvider("deepseek");
    call.setModel("deepseek-chat");
    call.setPromptTokens(120);
    call.setCompletionTokens(80);
    call.setTotalTokens(200);
    call.setSuccess(true);
    call.setDurationMs(1234L);

    repository.saveAndFlush(call);

    LlmCall loaded = repository.findBySessionId("s-1").get(0);
    assertEquals("deepseek", loaded.getProvider());
    assertEquals(200, loaded.getTotalTokens());
    assertTrue(loaded.isSuccess());
    assertNotNull(loaded.getCreatedAt());
  }

  @Test
  void 失败记录_errorMessage列真实存在且完整读回() {
    LlmCall call = new LlmCall();
    call.setSessionId("s-2");
    call.setProvider("kimi");
    call.setModel("moonshot-v1");
    call.setSuccess(false);
    call.setErrorMessage("connect timeout");
    call.setDurationMs(3000L);

    repository.saveAndFlush(call);

    LlmCall loaded = repository.findBySessionId("s-2").get(0);
    assertEquals(false, loaded.isSuccess());
    assertEquals("connect timeout", loaded.getErrorMessage()); // success/error_message 两列真实存在
  }

  @Test
  void trace上下文开启_落库自动携带traceId_接口零改动() {
    JpaLlmCallAuditor auditor = new JpaLlmCallAuditor(repository);
    String traceId;
    try (TraceContext.Scope scope = TraceContext.openIfAbsent()) {
      traceId = scope.traceId();
      // record 签名不变（R2 红线）：trace 由 Jpa 实现从环境自读
      auditor.record("s-trace", "agent", "deepseek", "m", null, null, true, null, 10L);
    }
    LlmCall loaded = repository.findByTraceId(traceId).get(0);
    assertEquals("s-trace", loaded.getSessionId());
    assertEquals(traceId, loaded.getTraceId());
  }

  @Test
  void trace上下文未开启_traceId为null且写入照常_旧行为等价() {
    JpaLlmCallAuditor auditor = new JpaLlmCallAuditor(repository);
    auditor.record("s-no-trace", "agent", "deepseek", "m", null, null, true, null, 10L);

    LlmCall loaded = repository.findBySessionId("s-no-trace").get(0);
    assertEquals(null, loaded.getTraceId()); // 未开启上下文=现状等价，可空列不阻断写入
    assertTrue(loaded.isSuccess());
  }

  @Test
  void 审计写入失败_必须阻断主链路() {
    LlmCallRepository broken = mock(LlmCallRepository.class);
    when(broken.save(any())).thenThrow(new RuntimeException("db locked"));
    JpaLlmCallAuditor auditor = new JpaLlmCallAuditor(broken);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                auditor.record(
                    "s-3", "agent", "deepseek", "m", null, null, true, null, List.of().size()));
    assertTrue(error.getMessage().contains("llm_calls"));
    assertEquals("db locked", error.getCause().getMessage());
  }

  @Test
  @DisplayName("016 聚合：窗口内的计数/求和/平均在一行里算完；空窗口归一为 0 与 null")
  void auditTotalsAggregateTheWindow() {
    Instant now = Instant.now();
    saveCall("agg-1", "deepseek", "deepseek-chat", "ops", 100, 50, 1000L, true, 100);
    saveCall("agg-2", "deepseek", "deepseek-chat", "ops", 200, 100, 500L, true, 300);
    saveCall("agg-3", "deepseek", "deepseek-chat", "ops", 300, 150, null, false, 200);
    repository.flush();

    LlmAuditTotals totals = repository.auditTotals(now.minusSeconds(3600), now.plusSeconds(3600));

    assertEquals(3L, totals.totalCount().longValue());
    assertEquals(2L, totals.successCount().longValue(), "失败那条不计入成功数");
    assertEquals(600L, totals.promptTokens().longValue());
    assertEquals(300L, totals.completionTokens().longValue());
    assertEquals(1500L, totals.costMicros().longValue(), "未计量（null）的行不进合计");
    assertEquals(2L, totals.measuredCostCount().longValue(), "已计量行数用于区分「未计量」与「合计 0 元」");
    assertEquals(200.0, totals.avgDurationMs(), 0.0001);

    LlmAuditTotals empty = repository.auditTotals(Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
    assertEquals(0L, empty.totalCount().longValue());
    assertEquals(0L, empty.measuredCostCount().longValue());
    assertNull(empty.successCount());
    assertNull(empty.promptTokens());
    assertNull(empty.costMicros());
    assertNull(empty.avgDurationMs());
  }

  @Test
  @DisplayName("016 分组：按模型 GROUP BY，组内 count/success/cost 原料正确，按 count 降序")
  void auditGroupsByModelGroupsAndCounts() {
    Instant now = Instant.now();
    saveCall("g-1", "deepseek", "model-a", "ops", 1, 1, 100L, true, 10);
    saveCall("g-2", "deepseek", "model-a", "ops", 1, 1, null, false, 10);
    saveCall("g-3", "kimi", "model-b", "ops", 1, 1, 50L, true, 10);
    repository.flush();

    List<LlmAuditGroup> groups =
        repository.auditGroupsByModel(now.minusSeconds(3600), now.plusSeconds(3600));

    assertEquals(2, groups.size());
    LlmAuditGroup first = groups.get(0);
    assertEquals("model-a", first.key(), "count 大的组排前面（SQL ORDER BY count DESC）");
    assertEquals(2L, first.totalCount().longValue());
    assertEquals(1L, first.successCount().longValue());
    assertEquals(100L, first.costMicros().longValue());
    assertEquals(1L, first.measuredCostCount().longValue());
    assertEquals("model-b", groups.get(1).key());
  }

  @Test
  @DisplayName("016 分组：Agent 为 null 时 SQL 返回 null 键，展示文案归服务层（真叫「(未归属)」的 Agent 是另一组）")
  void auditGroupsByAgentKeepsNullKey() {
    Instant now = Instant.now();
    saveCall("a-1", "deepseek", "m", null, 1, 1, 10L, true, 10);
    saveCall("a-2", "deepseek", "m", "ops", 1, 1, 10L, true, 10);
    saveCall("a-3", "deepseek", "m", "(未归属)", 1, 1, 10L, true, 10);
    repository.flush();

    List<String> keys = new ArrayList<>();
    repository
        .auditGroupsByAgent(now.minusSeconds(3600), now.plusSeconds(3600))
        .forEach(group -> keys.add(group.key()));

    assertEquals(3, keys.size());
    assertTrue(keys.contains(null), "未归属必须是 null 键");
    assertTrue(keys.contains("ops"));
    assertTrue(keys.contains("(未归属)"), "字面量同名的 Agent 不得被合并进未归属组");
  }

  @Test
  @DisplayName("016 明细：窗口 + 排序 + 截断都在 SQL（Pageable），同刻顺序由 id 决定")
  void findRecentInWindowAppliesOrderAndLimit() {
    Instant now = Instant.now();
    saveCall("r-1", "deepseek", "m", "ops", 1, 1, 1L, true, 10);
    saveCall("r-2", "deepseek", "m", "ops", 1, 1, 1L, true, 10);
    saveCall("r-3", "deepseek", "m", "ops", 1, 1, 1L, true, 10);
    repository.flush();

    List<LlmCall> page =
        repository.findRecentInWindow(
            now.minusSeconds(3600), now.plusSeconds(3600), PageRequest.of(0, 2));

    assertEquals(2, page.size(), "LIMIT 由 SQL 执行，不把整窗读回 Java");
    assertTrue(page.get(0).getId() > page.get(1).getId(), "同刻按 id DESC：最新插入的在最前");

    List<LlmCall> outside =
        repository.findRecentInWindow(
            Instant.EPOCH, Instant.EPOCH.plusSeconds(1), PageRequest.of(0, 10));
    assertTrue(outside.isEmpty(), "窗口外的行不得出现");
  }

  private void saveCall(
      String sessionId,
      String provider,
      String model,
      String profileName,
      int promptTokens,
      int completionTokens,
      Long costMicros,
      boolean success,
      long durationMs) {
    LlmCall call = new LlmCall();
    call.setSessionId(sessionId);
    call.setProvider(provider);
    call.setModel(model);
    call.setProfileName(profileName);
    call.setPromptTokens(promptTokens);
    call.setCompletionTokens(completionTokens);
    call.setTotalTokens(promptTokens + completionTokens);
    call.setCostMicros(costMicros);
    call.setSuccess(success);
    call.setDurationMs(durationMs);
    repository.saveAndFlush(call);
  }
}
