package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.audit.AuditMetricsService;
import io.oryxos.web.controller.dto.LlmCallView;
import io.oryxos.web.controller.dto.ToolInvocationView;
import io.oryxos.web.controller.dto.TraceTimelineView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 021 US1：时间线合并排序 / 汇总 / found=false / 列表视图 trace 维度。 */
class TraceTimelineTest {

  private static final String TRACE = "trace-021";

  private LlmCallRepository llmCallRepository;
  private ToolInvocationRepository toolInvocationRepository;
  private AuditMetricsService service;

  @BeforeEach
  void setUp() {
    llmCallRepository = mock(LlmCallRepository.class);
    toolInvocationRepository = mock(ToolInvocationRepository.class);
    service = new AuditMetricsService(llmCallRepository, toolInvocationRepository);
  }

  @Test
  void 合并排序_LLM与TOOL交错时间序_每步字段完整() {
    LlmCall first = llmCall(1L, Instant.parse("2026-09-01T10:00:01Z"), 812, 64, 876, 120L);
    LlmCall second = llmCall(2L, Instant.parse("2026-09-01T10:00:03Z"), 400, 140, 540, 140L);
    ToolInvocation tool = toolCall(Instant.parse("2026-09-01T10:00:02Z"));
    when(llmCallRepository.findByTraceId(TRACE)).thenReturn(List.of(second, first)); // 乱序进
    when(toolInvocationRepository.findByTraceId(TRACE)).thenReturn(List.of(tool));

    TraceTimelineView view = service.traceTimeline(TRACE);

    assertThat(view.found()).isTrue();
    assertThat(view.steps())
        .extracting(TraceTimelineView.StepView::type)
        .containsExactly("LLM", "TOOL", "LLM"); // 按 createdAt 时间序
    assertThat(view.steps()).extracting(TraceTimelineView.StepView::seq).containsExactly(1, 2, 3);

    TraceTimelineView.StepView llmStep = view.steps().get(0);
    assertThat(llmStep.name()).isEqualTo("glm-4-flash");
    assertThat(llmStep.promptTokens()).isEqualTo(812);
    assertThat(llmStep.totalTokens()).isEqualTo(876);
    assertThat(llmStep.costMicros()).isEqualTo(120L);

    TraceTimelineView.StepView toolStep = view.steps().get(1);
    assertThat(toolStep.name()).isEqualTo("save_memory");
    assertThat(toolStep.inputSummary()).contains("咖啡");
    assertThat(toolStep.resultSummary()).isEqualTo("OK");
    assertThat(toolStep.blockedBy()).isEqualTo("policy");
  }

  @Test
  void 汇总合计正确_token成本步数耗时() {
    LlmCall first = llmCall(1L, Instant.parse("2026-09-01T10:00:01Z"), 812, 64, 876, 120L);
    LlmCall second = llmCall(2L, Instant.parse("2026-09-01T10:00:03Z"), 400, 140, 540, 140L);
    ToolInvocation tool = toolCall(Instant.parse("2026-09-01T10:00:02Z"));
    when(llmCallRepository.findByTraceId(TRACE)).thenReturn(List.of(first, second));
    when(toolInvocationRepository.findByTraceId(TRACE)).thenReturn(List.of(tool));

    TraceTimelineView.SummaryView summary = service.traceTimeline(TRACE).summary();

    assertThat(summary.steps()).isEqualTo(3);
    assertThat(summary.llmCalls()).isEqualTo(2);
    assertThat(summary.toolCalls()).isEqualTo(1);
    assertThat(summary.totalTokens()).isEqualTo(1416);
    assertThat(summary.costMicros()).isEqualTo(260L);
    assertThat(summary.totalDurationMs()).isEqualTo(1200 + 900 + 15);
  }

  @Test
  void 未计量成本_汇总costMicros为null() {
    LlmCall unmeasured = llmCall(1L, Instant.parse("2026-09-01T10:00:01Z"), null, null, null, null);
    when(llmCallRepository.findByTraceId(TRACE)).thenReturn(List.of(unmeasured));
    when(toolInvocationRepository.findByTraceId(TRACE)).thenReturn(List.of());

    assertThat(service.traceTimeline(TRACE).summary().costMicros()).isNull();
  }

  @Test
  void 未命中_found为false空时间线_不抛异常() {
    when(llmCallRepository.findByTraceId("missing")).thenReturn(List.of());
    when(toolInvocationRepository.findByTraceId("missing")).thenReturn(List.of());

    TraceTimelineView view = service.traceTimeline("missing");

    assertThat(view.found()).isFalse();
    assertThat(view.steps()).isEmpty();
    assertThat(view.summary().steps()).isZero();
  }

  @Test
  void 长摘要截断200字符() {
    ToolInvocation tool = toolCall(Instant.parse("2026-09-01T10:00:02Z"));
    tool.setInputJson("x".repeat(500));
    when(llmCallRepository.findByTraceId(TRACE)).thenReturn(List.of());
    when(toolInvocationRepository.findByTraceId(TRACE)).thenReturn(List.of(tool));

    String summary = service.traceTimeline(TRACE).steps().get(0).inputSummary();
    assertThat(summary).hasSize(201).endsWith("…"); // 200 字符 + 截断标记
  }

  @Test
  void 含敏感参数的步骤_展示掩码_库侧原值不动() {
    ToolInvocation tool = toolCall(Instant.parse("2026-09-01T10:00:02Z"));
    tool.setInputJson("{\"password\":\"p@ss123\",\"key\":\"sk-abcdefgh12345678\"}");
    when(llmCallRepository.findByTraceId(TRACE)).thenReturn(List.of());
    when(toolInvocationRepository.findByTraceId(TRACE)).thenReturn(List.of(tool));

    String shown = service.traceTimeline(TRACE).steps().get(0).inputSummary();

    assertThat(shown).contains("p@ss****").contains("sk-a****").doesNotContain("p@ss123");
    // 展示层脱敏不回写实体（落库原文，Clarifications 裁决）
    assertThat(tool.getInputJson()).contains("p@ss123").contains("sk-abcdefgh12345678");
  }

  @Test
  void 列表视图traceId字段在位() {
    LlmCall call = llmCall(1L, Instant.parse("2026-09-01T10:00:01Z"), 1, 1, 2, null);
    ToolInvocation tool = toolCall(Instant.parse("2026-09-01T10:00:02Z"));

    assertThat(LlmCallView.from(call).traceId()).isEqualTo(TRACE);
    assertThat(ToolInvocationView.from(tool).traceId()).isEqualTo(TRACE);
  }

  @Test
  void traceId为null的旧行_列表视图正常返回() {
    // FR-010 旧数据兼容：升级前的旧行 trace 为空，既有列表查询照常序列化
    LlmCall legacy = llmCall(9L, Instant.parse("2026-08-01T00:00:00Z"), 1, 1, 2, null);
    legacy.setTraceId(null);

    LlmCallView view = LlmCallView.from(legacy);
    assertThat(view.traceId()).isNull();
    assertThat(view.model()).isEqualTo("glm-4-flash");
  }

  private static LlmCall llmCall(
      Long id,
      Instant at,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Long costMicros) {
    LlmCall call = new LlmCall();
    call.setSessionId("s-1");
    call.setProvider("zhipu");
    call.setModel("glm-4-flash");
    call.setPromptTokens(promptTokens);
    call.setCompletionTokens(completionTokens);
    call.setTotalTokens(totalTokens);
    call.setCostMicros(costMicros);
    call.setTraceId(TRACE);
    call.setSuccess(true);
    call.setDurationMs(id == 1L ? 1200 : 900);
    setCreatedAt(call, at);
    return call;
  }

  private static ToolInvocation toolCall(Instant at) {
    ToolInvocation tool = new ToolInvocation();
    tool.setSessionId("s-1");
    tool.setToolName("save_memory");
    tool.setInputJson("{\"content\":\"喜欢咖啡\"}");
    tool.setResultJson("OK");
    tool.setTraceId(TRACE);
    tool.setSuccess(false);
    tool.setBlockedBy("policy");
    tool.setDurationMs(15);
    setCreatedAt(tool, at);
    return tool;
  }

  /** createdAt 由 @PrePersist 生成、无 setter——测试经反射注入固定时刻以钉死排序断言。 */
  private static void setCreatedAt(Object entity, Instant at) {
    try {
      var field = entity.getClass().getDeclaredField("createdAt");
      field.setAccessible(true);
      field.set(entity, at);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
