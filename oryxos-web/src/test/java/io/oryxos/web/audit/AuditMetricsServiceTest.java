package io.oryxos.web.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
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
    when(llmCalls.findByCreatedAtBetween(any(), any()))
        .thenReturn(
            List.of(
                llmCall("ops", "deepseek-chat", 1000, 2000, 5000L, true, 100),
                llmCall("ops", "deepseek-chat", 500, 500, 1500L, false, 300)));

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
    when(llmCalls.findByCreatedAtBetween(any(), any()))
        .thenReturn(List.of(llmCall("ops", "deepseek-chat", 1000, 2000, null, true, 100)));

    LlmSummaryView s = service.llmSummary(Instant.MIN, Instant.MAX);

    assertThat(s.totalCostMicros()).isNull();
  }

  @Test
  @DisplayName("工具汇总：次数/成功率/平均耗时正确聚合")
  void toolSummaryAggregatesCorrectly() {
    when(toolInvocations.findByCreatedAtBetween(any(), any()))
        .thenReturn(
            List.of(
                toolInvocation("ops", "read_file", true, 10),
                toolInvocation("ops", "shell", false, 50),
                toolInvocation("ops", "http_get", true, 30)));

    ToolSummaryView s = service.toolSummary(Instant.MIN, Instant.MAX);

    assertThat(s.count()).isEqualTo(3);
    assertThat(s.successCount()).isEqualTo(2);
    assertThat(s.avgDurationMs()).isEqualTo(30);
  }

  @Test
  @DisplayName("按模型分组：次数/成功数/成本正确归集并降序")
  void llmByModelGroupsAndSumsCost() {
    when(llmCalls.findByCreatedAtBetween(any(), any()))
        .thenReturn(
            List.of(
                llmCall("ops", "deepseek-chat", 100, 100, 1000L, true, 10),
                llmCall("ops", "deepseek-chat", 100, 100, 2000L, true, 10),
                llmCall("ops", "gpt-5.5", 100, 100, 500L, false, 10)));

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
    when(toolInvocations.findByCreatedAtBetween(any(), any()))
        .thenReturn(
            List.of(
                toolInvocation("ops", "read_file", true, 10),
                toolInvocation("ops", "read_file", false, 10),
                toolInvocation("ops", "shell", true, 10)));

    List<AuditGroupView> groups = service.toolByName(Instant.MIN, Instant.MAX);

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).key()).isEqualTo("read_file");
    assertThat(groups.get(0).count()).isEqualTo(2);
  }

  @Test
  @DisplayName("按 Agent 分组：未归属（null profile）标记为「(未归属)」")
  void llmByAgentGroupsAndMarksUnassigned() {
    when(llmCalls.findByCreatedAtBetween(any(), any()))
        .thenReturn(
            List.of(
                llmCall("ops", "deepseek-chat", 100, 100, 1000L, true, 10),
                llmCall(null, "deepseek-chat", 100, 100, 500L, true, 10)));

    List<AuditGroupView> groups = service.llmByAgent(Instant.MIN, Instant.MAX);

    assertThat(groups).hasSize(2);
    assertThat(groups).extracting(AuditGroupView::key).containsExactlyInAnyOrder("ops", "(未归属)");
  }

  private static LlmCall llmCall(
      String profile,
      String model,
      int prompt,
      int completion,
      Long costMicros,
      boolean success,
      long durationMs) {
    LlmCall c = new LlmCall();
    c.setProfileName(profile);
    c.setProvider("deepseek");
    c.setModel(model);
    c.setPromptTokens(prompt);
    c.setCompletionTokens(completion);
    c.setTotalTokens(prompt + completion);
    c.setCostMicros(costMicros);
    c.setSuccess(success);
    c.setDurationMs(durationMs);
    return c;
  }

  private static ToolInvocation toolInvocation(
      String profile, String toolName, boolean success, long durationMs) {
    ToolInvocation t = new ToolInvocation();
    t.setProfileName(profile);
    t.setToolName(toolName);
    t.setSuccess(success);
    t.setDurationMs(durationMs);
    return t;
  }
}
