package io.oryxos.web.audit;

import io.oryxos.storage.LlmAuditGroup;
import io.oryxos.storage.LlmAuditTotals;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolAuditTotals;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.controller.dto.AuditGroupView;
import io.oryxos.web.controller.dto.LlmCallView;
import io.oryxos.web.controller.dto.LlmSummaryView;
import io.oryxos.web.controller.dto.ToolInvocationView;
import io.oryxos.web.controller.dto.ToolSummaryView;
import io.oryxos.web.controller.dto.TraceTimelineView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;

/**
 * 审计聚合（016 审计看板）：只读两张审计表。
 *
 * <p>聚合、分组、明细截断一律下推到 SQL（聚合查询 / {@code GROUP BY} / {@link PageRequest}）——审计表 append-only， 默认窗口 30
 * 天可以是几十万行，整窗实体进堆会连累同进程的 LLM 服务。这里只做口径归一：成功率、平均耗时取整、 成本「未计量 → null」、以及未归属键的展示文案。
 *
 * <p>成本口径（G2）：未计量（costMicros=null，失败或未定价）的调用不计入 totalCostMicros；无任何已计量调用时返回 null。
 */
@org.springframework.stereotype.Service
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "repository 为 Spring 注入的共享单例，构造注入存同一引用正是意图。")
public class AuditMetricsService {

  private static final String UNASSIGNED = "(未归属)";

  private final LlmCallRepository llmCallRepository;
  private final ToolInvocationRepository toolInvocationRepository;

  public AuditMetricsService(
      LlmCallRepository llmCallRepository, ToolInvocationRepository toolInvocationRepository) {
    this.llmCallRepository = llmCallRepository;
    this.toolInvocationRepository = toolInvocationRepository;
  }

  public LlmSummaryView llmSummary(Instant from, Instant to) {
    LlmAuditTotals totals = llmCallRepository.auditTotals(from, to);
    long count = zeroIfNull(totals.totalCount());
    long successCount = zeroIfNull(totals.successCount());
    long measuredCosts = zeroIfNull(totals.measuredCostCount());
    long avgDurationMs =
        count == 0 ? 0 : Math.round(totals.avgDurationMs() == null ? 0.0 : totals.avgDurationMs());
    return new LlmSummaryView(
        count,
        successCount,
        successRate(count, successCount),
        zeroIfNull(totals.promptTokens()),
        zeroIfNull(totals.completionTokens()),
        measuredCosts == 0 ? null : totals.costMicros(),
        avgDurationMs);
  }

  public ToolSummaryView toolSummary(Instant from, Instant to) {
    ToolAuditTotals totals = toolInvocationRepository.auditTotals(from, to);
    long count = zeroIfNull(totals.totalCount());
    long successCount = zeroIfNull(totals.successCount());
    long avgDurationMs =
        count == 0 ? 0 : Math.round(totals.avgDurationMs() == null ? 0.0 : totals.avgDurationMs());
    return new ToolSummaryView(
        count, successCount, successRate(count, successCount), avgDurationMs);
  }

  public List<AuditGroupView> llmByModel(Instant from, Instant to) {
    return toLlmGroups(llmCallRepository.auditGroupsByModel(from, to));
  }

  public List<AuditGroupView> llmByProvider(Instant from, Instant to) {
    return toLlmGroups(llmCallRepository.auditGroupsByProvider(from, to));
  }

  public List<AuditGroupView> llmByAgent(Instant from, Instant to) {
    return toLlmGroups(llmCallRepository.auditGroupsByAgent(from, to));
  }

  public List<AuditGroupView> toolByName(Instant from, Instant to) {
    return toolInvocationRepository.auditGroupsByName(from, to).stream()
        .map(
            group ->
                new AuditGroupView(
                    groupKey(group.key()),
                    zeroIfNull(group.totalCount()),
                    zeroIfNull(group.successCount()),
                    null))
        .toList();
  }

  public List<LlmCallView> llmList(Instant from, Instant to, int limit) {
    return llmCallRepository.findRecentInWindow(from, to, page(limit)).stream()
        .map(LlmCallView::from)
        .toList();
  }

  public List<ToolInvocationView> toolList(Instant from, Instant to, int limit) {
    return toolList(from, to, limit, null, null);
  }

  public List<ToolInvocationView> toolList(Instant from, Instant to, int limit, String blockedBy) {
    return toolList(from, to, limit, blockedBy, null);
  }

  /**
   * 020：blockedBy 非空时只返回该拦截来源的记录（如 'policy'=策略拒绝，FR-006 可筛口径）。 024：executionBackend
   * 非空时按执行后端筛选（SC-007；历史行 NULL ≡ local，不匹配 'docker' 但匹配 null 查询由前端处理）。
   */
  public List<ToolInvocationView> toolList(
      Instant from, Instant to, int limit, String blockedBy, String executionBackend) {
    return toolInvocationRepository
        .findRecentInWindow(from, to, blockedBy, executionBackend, page(limit))
        .stream()
        .map(ToolInvocationView::from)
        .toList();
  }

  /**
   * 021 单轮全链路回放：按 trace 取两表记录，按 createdAt（完成时刻）合并排序为时间线 steps + 汇总。 TOOL 步摘要先截断 200
   * 字符再脱敏（Redactor）；未命中返回 found=false 空时间线（200 不报错）。
   */
  public TraceTimelineView traceTimeline(String traceId) {
    List<LlmCall> llmCalls = llmCallRepository.findByTraceId(traceId);
    List<ToolInvocation> toolCalls = toolInvocationRepository.findByTraceId(traceId);
    if (llmCalls.isEmpty() && toolCalls.isEmpty()) {
      return new TraceTimelineView(
          traceId, false, List.of(), new TraceTimelineView.SummaryView(0, 0, 0, 0L, null, 0L));
    }

    record Event(Instant at, Object row) {}
    List<Event> events = new ArrayList<>();
    llmCalls.forEach(c -> events.add(new Event(c.getCreatedAt(), c)));
    toolCalls.forEach(t -> events.add(new Event(t.getCreatedAt(), t)));
    events.sort(Comparator.comparing(Event::at, Comparator.nullsLast(Comparator.naturalOrder())));

    List<TraceTimelineView.StepView> steps = new ArrayList<>(events.size());
    for (int i = 0; i < events.size(); i++) {
      Object row = events.get(i).row();
      steps.add(
          row instanceof LlmCall llm ? llmStep(i + 1, llm) : toolStep(i + 1, (ToolInvocation) row));
    }

    long totalTokens =
        llmCalls.stream().mapToLong(c -> c.getTotalTokens() == null ? 0 : c.getTotalTokens()).sum();
    long totalDurationMs =
        llmCalls.stream().mapToLong(LlmCall::getDurationMs).sum()
            + toolCalls.stream().mapToLong(ToolInvocation::getDurationMs).sum();
    TraceTimelineView.SummaryView summary =
        new TraceTimelineView.SummaryView(
            steps.size(),
            llmCalls.size(),
            toolCalls.size(),
            totalTokens,
            sumCost(llmCalls),
            totalDurationMs);
    return new TraceTimelineView(traceId, true, steps, summary);
  }

  private static TraceTimelineView.StepView llmStep(int seq, LlmCall c) {
    return new TraceTimelineView.StepView(
        seq,
        "LLM",
        c.getModel(),
        c.isSuccess(),
        c.getDurationMs(),
        c.getCreatedAt(),
        c.getPromptTokens(),
        c.getCompletionTokens(),
        c.getTotalTokens(),
        c.getCostMicros(),
        null,
        null,
        summarize(c.getErrorMessage()),
        null);
  }

  private static TraceTimelineView.StepView toolStep(int seq, ToolInvocation t) {
    return new TraceTimelineView.StepView(
        seq,
        "TOOL",
        t.getToolName(),
        t.isSuccess(),
        t.getDurationMs(),
        t.getCreatedAt(),
        null,
        null,
        null,
        null,
        summarize(t.getInputJson()),
        summarize(t.getResultJson()),
        summarize(t.getErrorMessage()),
        t.getBlockedBy());
  }

  /** 展示层摘要：先截断 200 字符，再统一脱敏（Redactor，FR-007/FR-008）；落库原文不动。 */
  private static String summarize(String value) {
    if (value == null) {
      return null;
    }
    String truncated = value.length() <= 200 ? value : value.substring(0, 200) + "…";
    return Redactor.redact(truncated);
  }

  /** 分组行 → 视图：排序已在 SQL（count DESC）；成本按组内「是否有已计量调用」判 null。 */
  private static List<AuditGroupView> toLlmGroups(List<LlmAuditGroup> groups) {
    return groups.stream()
        .map(
            group ->
                new AuditGroupView(
                    groupKey(group.key()),
                    zeroIfNull(group.totalCount()),
                    zeroIfNull(group.successCount()),
                    zeroIfNull(group.measuredCostCount()) == 0 ? null : group.costMicros()))
        .toList();
  }

  /** 未归属只来自 SQL 的 null 键；真叫「(未归属)」的 Agent 是另一个组，不会被合并。 */
  private static String groupKey(String raw) {
    return raw == null ? UNASSIGNED : raw;
  }

  private static double successRate(long count, long successCount) {
    return count == 0 ? 0.0 : (double) successCount / count;
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0 : value;
  }

  private static PageRequest page(int limit) {
    return PageRequest.of(0, limit);
  }

  /** 只累加已计量成本；无任何已计量调用时返回 null（未计量，区别于 0）。 */
  private static Long sumCost(List<LlmCall> calls) {
    long measured = calls.stream().filter(c -> c.getCostMicros() != null).count();
    if (measured == 0) {
      return null;
    }
    return calls.stream()
        .filter(c -> c.getCostMicros() != null)
        .mapToLong(LlmCall::getCostMicros)
        .sum();
  }
}
