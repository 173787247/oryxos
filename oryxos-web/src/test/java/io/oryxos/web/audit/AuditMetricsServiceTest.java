package io.oryxos.web.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.oryxos.storage.LlmAuditGroup;
import io.oryxos.storage.LlmAuditTotals;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolAuditGroup;
import io.oryxos.storage.ToolAuditTotals;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.controller.dto.AuditGroupView;
import io.oryxos.web.controller.dto.LlmSummaryView;
import io.oryxos.web.controller.dto.ToolSummaryView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/** 016 审计看板验收：SC-002 聚合正确性——LLM/工具汇总、分组、成本口径、Agent 归属。 */
class AuditMetricsServiceTest {

  private LlmCallRepository llmCalls;
  private ToolInvocationRepository toolInvocations;
  private AuditMetricsService service;

  @BeforeEach
  void setUp() {
    llmCalls = mock(LlmCallRepository.class);
    toolInvocations = mock(ToolInvocationRepository.class);
    service = new AuditMetricsService(llmCalls, toolInvocations);
  }

  @Test
  @DisplayName("LLM 汇总：次数/成功率/token/成本/平均耗时正确聚合")
  void llmSummaryAggregatesCorrectly() {
    when(llmCalls.auditTotals(any(), any()))
        .thenReturn(new LlmAuditTotals(2L, 1L, 1500L, 2500L, 6500L, 2L, 200.0));

    LlmSummaryView s = service.llmSummary(Instant.MIN, Instant.MAX);

    assertThat(s.count()).isEqualTo(2);
    assertThat(s.successCount()).isEqualTo(1);
    assertThat(s.successRate()).isEqualTo(0.5);
    assertThat(s.totalPromptTokens()).isEqualTo(1500);
    assertThat(s.totalCompletionTokens()).isEqualTo(2500);
    assertThat(s.totalCostMicros()).isEqualTo(6500L);
    assertThat(s.avgDurationMs()).isEqualTo(200);
  }

  @Test
  @DisplayName("全部成本未计量（null）时总成本返回 null 而非 0")
  void llmSummaryWithAllUnmeasuredCostReturnsNull() {
    when(llmCalls.auditTotals(any(), any()))
        .thenReturn(new LlmAuditTotals(1L, 1L, 1000L, 2000L, null, 0L, 100.0));

    LlmSummaryView s = service.llmSummary(Instant.MIN, Instant.MAX);

    assertThat(s.totalCostMicros()).isNull();
  }

  @Test
  @DisplayName("工具汇总：次数/成功率/平均耗时正确聚合")
  void toolSummaryAggregatesCorrectly() {
    when(toolInvocations.auditTotals(any(), any())).thenReturn(new ToolAuditTotals(3L, 2L, 30.0));

    ToolSummaryView s = service.toolSummary(Instant.MIN, Instant.MAX);

    assertThat(s.count()).isEqualTo(3);
    assertThat(s.successCount()).isEqualTo(2);
    assertThat(s.avgDurationMs()).isEqualTo(30);
  }

  @Test
  @DisplayName("空窗口：聚合行全 null 时归一到 0（成本仍为 null）")
  void emptyWindowNormalisesToZeros() {
    when(llmCalls.auditTotals(any(), any()))
        .thenReturn(new LlmAuditTotals(0L, null, null, null, null, 0L, null));
    when(toolInvocations.auditTotals(any(), any())).thenReturn(new ToolAuditTotals(0L, null, null));

    LlmSummaryView llm = service.llmSummary(Instant.MIN, Instant.MAX);
    ToolSummaryView tool = service.toolSummary(Instant.MIN, Instant.MAX);

    assertThat(llm.count()).isZero();
    assertThat(llm.successRate()).isZero();
    assertThat(llm.totalPromptTokens()).isZero();
    assertThat(llm.totalCostMicros()).isNull();
    assertThat(llm.avgDurationMs()).isZero();
    assertThat(tool.count()).isZero();
    assertThat(tool.successRate()).isZero();
    assertThat(tool.avgDurationMs()).isZero();
  }

  @Test
  @DisplayName("按模型分组：次数/成功数/成本正确归集并降序")
  void llmByModelGroupsAndSumsCost() {
    when(llmCalls.auditGroupsByModel(any(), any()))
        .thenReturn(
            List.of(
                new LlmAuditGroup("deepseek-chat", 2L, 2L, 3000L, 2L),
                new LlmAuditGroup("gpt-5.5", 1L, 0L, 500L, 1L)));

    List<AuditGroupView> groups = service.llmByModel(Instant.MIN, Instant.MAX);

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).key()).isEqualTo("deepseek-chat");
    assertThat(groups.get(0).count()).isEqualTo(2);
    assertThat(groups.get(0).totalCostMicros()).isEqualTo(3000L);
    assertThat(groups.get(1).key()).isEqualTo("gpt-5.5");
  }

  @Test
  @DisplayName("按工具名分组：次数归集")
  void toolByNameGroups() {
    when(toolInvocations.auditGroupsByName(any(), any()))
        .thenReturn(
            List.of(new ToolAuditGroup("read_file", 2L, 1L), new ToolAuditGroup("shell", 1L, 1L)));

    List<AuditGroupView> groups = service.toolByName(Instant.MIN, Instant.MAX);

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).key()).isEqualTo("read_file");
    assertThat(groups.get(0).count()).isEqualTo(2);
  }

  @Test
  @DisplayName("按 Agent 分组：未归属（SQL 返回 null 键）标记为「(未归属)」，同名字面量不合并")
  void llmByAgentGroupsAndMarksUnassigned() {
    when(llmCalls.auditGroupsByAgent(any(), any()))
        .thenReturn(
            List.of(
                new LlmAuditGroup("ops", 1L, 1L, 1000L, 1L),
                new LlmAuditGroup(null, 1L, 1L, 500L, 1L),
                new LlmAuditGroup("(未归属)", 1L, 1L, null, 0L)));

    List<AuditGroupView> groups = service.llmByAgent(Instant.MIN, Instant.MAX);

    assertThat(groups).hasSize(3);
    assertThat(groups)
        .extracting(AuditGroupView::key)
        .containsExactlyInAnyOrder("ops", "(未归属)", "(未归属)");
    // 第 2 项来自 SQL 的 null 键（成本 500，有已计量行）；第 3 项是真叫「(未归属)」的 Agent（未计量 → null）
    assertThat(groups.get(1).key()).isEqualTo("(未归属)");
    assertThat(groups.get(1).totalCostMicros()).isEqualTo(500L);
    assertThat(groups.get(2).totalCostMicros()).isNull();
  }

  @Test
  @DisplayName("六个入口只调用聚合/分页方法：任何退回全量 List 的改动都会在这里红")
  void readsOnlyAggregatesAndPagesNeverTheWholeWindow() {
    when(llmCalls.auditTotals(any(), any()))
        .thenReturn(new LlmAuditTotals(0L, 0L, 0L, 0L, null, 0L, null));
    when(toolInvocations.auditTotals(any(), any())).thenReturn(new ToolAuditTotals(0L, 0L, null));
    when(llmCalls.auditGroupsByModel(any(), any())).thenReturn(List.of());
    when(llmCalls.auditGroupsByProvider(any(), any())).thenReturn(List.of());
    when(llmCalls.auditGroupsByAgent(any(), any())).thenReturn(List.of());
    when(toolInvocations.auditGroupsByName(any(), any())).thenReturn(List.of());
    when(llmCalls.findRecentInWindow(any(), any(), any())).thenReturn(List.of());
    when(toolInvocations.findRecentInWindow(any(), any(), any(), any(), any()))
        .thenReturn(List.of());

    service.llmSummary(Instant.MIN, Instant.MAX);
    service.toolSummary(Instant.MIN, Instant.MAX);
    service.llmByModel(Instant.MIN, Instant.MAX);
    service.llmByProvider(Instant.MIN, Instant.MAX);
    service.llmByAgent(Instant.MIN, Instant.MAX);
    service.toolByName(Instant.MIN, Instant.MAX);
    service.llmList(Instant.MIN, Instant.MAX, 10);
    service.toolList(Instant.MIN, Instant.MAX, 10);

    verifyNoMoreInteractions(ignoreStubs(llmCalls, toolInvocations));
  }

  @Test
  @DisplayName("明细下钻：limit 与过滤条件原样下推（PageRequest 大小 + 两个筛选参数）")
  void listsPushLimitAndFiltersIntoTheQuery() {
    LlmCall call = new LlmCall();
    call.setSessionId("s-1");
    call.setProvider("deepseek");
    call.setModel("m");
    call.setSuccess(true);
    call.setDurationMs(10L);
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId("s-1");
    invocation.setToolName("read_file");
    invocation.setSuccess(true);
    invocation.setDurationMs(10L);
    when(llmCalls.findRecentInWindow(any(), eq(Instant.MAX), eq(PageRequest.of(0, 5))))
        .thenReturn(List.of(call));
    when(toolInvocations.findRecentInWindow(
            any(), eq(Instant.MAX), eq("policy"), eq("docker"), eq(PageRequest.of(0, 7))))
        .thenReturn(List.of(invocation));

    assertThat(service.llmList(Instant.MIN, Instant.MAX, 5)).hasSize(1);
    assertThat(service.toolList(Instant.MIN, Instant.MAX, 7, "policy", "docker")).hasSize(1);

    verify(llmCalls).findRecentInWindow(Instant.MIN, Instant.MAX, PageRequest.of(0, 5));
    verify(toolInvocations)
        .findRecentInWindow(Instant.MIN, Instant.MAX, "policy", "docker", PageRequest.of(0, 7));
  }
}
