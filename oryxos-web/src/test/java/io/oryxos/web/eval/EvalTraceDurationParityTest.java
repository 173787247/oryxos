package io.oryxos.web.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.eval.EvalFixtureLoader;
import io.oryxos.core.eval.EvalSuiteResult;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.audit.AuditMetricsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 同一 trace 只允许一套耗时口径：审核导出（{@link EvalExportService}）与审计看板时间线（{@link AuditMetricsService}）读的是同一对表、
 * 同一个 traceId，必须给出同一个数。
 *
 * <p>为什么必须是相加而不是取两支较大值：本仓执行模型是同步串行（宪法 VII 不引异步），LLM 调用与工具调用前后相接，trace 的实际跨度是两支之和。
 * 取较大值只在「只有一支有行」时才碰巧正确，在真实 trace 上系统性低估；而这个值经 {@code AuditEvalExporter} → {@code EvalFixtureLoader}
 * → {@code EvalHarness.latencyMsAvg} 直流入 {@code EvalRegressionGate} 的时延阈值—— 低估会让时延回归在门禁上被判为通过。
 *
 * <p>断言走的是 {@link EvalFixtureLoader#loadSuite(Path)}：门禁读的就是这份 suite JSON 的 {@code latencyMs}，
 * 所以这里比的是两处**对外可见**的结论，而不是某个内部变量。
 */
class EvalTraceDurationParityTest {

  private static final String TRACE = "trace-048";
  private static final long LLM_BRANCH_MS = 1200 + 900;
  private static final long TOOL_BRANCH_MS = 15 + 25;

  private LlmCallRepository llmCallRepository;
  private ToolInvocationRepository toolInvocationRepository;
  private EvalExportService exportService;
  private AuditMetricsService auditMetricsService;

  @TempDir Path tmp;

  @BeforeEach
  void setUp() {
    llmCallRepository = mock(LlmCallRepository.class);
    toolInvocationRepository = mock(ToolInvocationRepository.class);
    exportService = new EvalExportService(llmCallRepository, toolInvocationRepository);
    auditMetricsService = new AuditMetricsService(llmCallRepository, toolInvocationRepository);
  }

  @Test
  @DisplayName("两支都有行：导出耗时与时间线合计一致，且等于两支相加（不是较大值）")
  void exportAndTimelineAgreeOnTheSumOfBothBranches() throws Exception {
    stub(llmCall(1L, 1200L), llmCall(2L, 900L));
    stubTools(tool(15L), tool(25L));

    long exported = exportedLatencyMs();
    long timeline = auditMetricsService.traceTimeline(TRACE).summary().totalDurationMs();

    assertThat(exported).isEqualTo(timeline).isEqualTo(LLM_BRANCH_MS + TOOL_BRANCH_MS);
  }

  @Test
  @DisplayName("工具支更重：耗时是相加，不能被压到较大值")
  void toolHeavyTraceIsNotCollapsedToTheLargerBranch() throws Exception {
    stub(llmCall(1L, LLM_BRANCH_MS));
    stubTools(tool(3000L), tool(200L));

    long exported = exportedLatencyMs();
    long timeline = auditMetricsService.traceTimeline(TRACE).summary().totalDurationMs();

    assertThat(exported).isEqualTo(timeline).isEqualTo(LLM_BRANCH_MS + 3200L);
  }

  @Test
  @DisplayName("只有一支有行：相加与较大值重合，两处仍一致")
  void singleBranchTraceStillAgrees() throws Exception {
    stub(llmCall(1L, 1200L), llmCall(2L, 900L));
    stubTools();

    long exported = exportedLatencyMs();
    long timeline = auditMetricsService.traceTimeline(TRACE).summary().totalDurationMs();

    assertThat(exported).isEqualTo(timeline).isEqualTo(LLM_BRANCH_MS);
  }

  /** 导出 → suite JSON → 门禁读到的 latencyMs（与 EvalRegressionGate 消费的是同一个值）。 */
  private long exportedLatencyMs() throws Exception {
    Path suite = tmp.resolve("suite.json");
    Files.writeString(suite, exportService.exportTraceSuite(TRACE));

    EvalSuiteResult scored = EvalFixtureLoader.loadSuite(suite);
    assertThat(scored.cases()).hasSize(1);
    assertThat(scored.metrics().latencyMsAvg()).isEqualTo(scored.cases().get(0).latencyMs());
    return scored.cases().get(0).latencyMs();
  }

  private void stub(LlmCall... llms) {
    when(llmCallRepository.findByTraceId(TRACE)).thenReturn(List.of(llms));
  }

  private void stubTools(ToolInvocation... tools) {
    when(toolInvocationRepository.findByTraceId(TRACE)).thenReturn(List.of(tools));
  }

  private static LlmCall llmCall(Long id, long durationMs) {
    LlmCall call = new LlmCall();
    call.setSessionId("s-1");
    call.setProvider("zhipu");
    call.setModel("glm-4-flash");
    call.setTraceId(TRACE);
    call.setSuccess(true);
    call.setDurationMs(durationMs);
    setCreatedAt(call, Instant.parse("2026-09-01T10:00:0" + id + "Z"));
    return call;
  }

  private static ToolInvocation tool(long durationMs) {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId("s-1");
    invocation.setToolName("save_memory");
    invocation.setTraceId(TRACE);
    invocation.setSuccess(true);
    invocation.setDurationMs(durationMs);
    setCreatedAt(invocation, Instant.parse("2026-09-01T10:00:05Z"));
    return invocation;
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
