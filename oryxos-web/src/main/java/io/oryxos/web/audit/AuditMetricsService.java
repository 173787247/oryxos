package io.oryxos.web.audit;

import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.controller.dto.AuditGroupView;
import io.oryxos.web.controller.dto.LlmCallView;
import io.oryxos.web.controller.dto.LlmSummaryView;
import io.oryxos.web.controller.dto.ToolInvocationView;
import io.oryxos.web.controller.dto.ToolSummaryView;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审计聚合（016 审计看板）：只读两张审计表，Java 内存 for 循环聚合（对齐 KnowledgeMetricsService 范式，无 SQL GROUP BY）。
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
    List<LlmCall> calls = llmCallRepository.findByCreatedAtBetween(from, to);
    long count = calls.size();
    long successCount = calls.stream().filter(LlmCall::isSuccess).count();
    double successRate = count == 0 ? 0.0 : (double) successCount / count;
    long totalPromptTokens =
        calls.stream().mapToLong(c -> c.getPromptTokens() == null ? 0 : c.getPromptTokens()).sum();
    long totalCompletionTokens =
        calls.stream()
            .mapToLong(c -> c.getCompletionTokens() == null ? 0 : c.getCompletionTokens())
            .sum();
    Long totalCostMicros = sumCost(calls);
    long avgDurationMs =
        count == 0
            ? 0
            : Math.round(calls.stream().mapToLong(LlmCall::getDurationMs).average().orElse(0));
    return new LlmSummaryView(
        count,
        successCount,
        successRate,
        totalPromptTokens,
        totalCompletionTokens,
        totalCostMicros,
        avgDurationMs);
  }

  public ToolSummaryView toolSummary(Instant from, Instant to) {
    List<ToolInvocation> invs = toolInvocationRepository.findByCreatedAtBetween(from, to);
    long count = invs.size();
    long successCount = invs.stream().filter(ToolInvocation::isSuccess).count();
    double successRate = count == 0 ? 0.0 : (double) successCount / count;
    long avgDurationMs =
        count == 0
            ? 0
            : Math.round(
                invs.stream().mapToLong(ToolInvocation::getDurationMs).average().orElse(0));
    return new ToolSummaryView(count, successCount, successRate, avgDurationMs);
  }

  public List<AuditGroupView> llmByModel(Instant from, Instant to) {
    return llmGroup(from, to, LlmCall::getModel);
  }

  public List<AuditGroupView> llmByProvider(Instant from, Instant to) {
    return llmGroup(from, to, LlmCall::getProvider);
  }

  public List<AuditGroupView> llmByAgent(Instant from, Instant to) {
    return llmGroup(from, to, LlmCall::getProfileName);
  }

  public List<AuditGroupView> toolByName(Instant from, Instant to) {
    Map<String, List<ToolInvocation>> grouped =
        toolInvocationRepository.findByCreatedAtBetween(from, to).stream()
            .collect(
                Collectors.groupingBy(t -> t.getToolName() == null ? UNASSIGNED : t.getToolName()));
    return grouped.entrySet().stream()
        .map(
            e -> {
              List<ToolInvocation> ts = e.getValue();
              long successCount = ts.stream().filter(ToolInvocation::isSuccess).count();
              return new AuditGroupView(e.getKey(), ts.size(), successCount, null);
            })
        .sorted(Comparator.comparingLong(AuditGroupView::count).reversed())
        .toList();
  }

  public List<LlmCallView> llmList(Instant from, Instant to, int limit) {
    return llmCallRepository.findByCreatedAtBetween(from, to).stream()
        .sorted(
            Comparator.comparing(
                LlmCall::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(limit)
        .map(LlmCallView::from)
        .toList();
  }

  public List<ToolInvocationView> toolList(Instant from, Instant to, int limit) {
    return toolList(from, to, limit, null);
  }

  /** 020：blockedBy 非空时只返回该拦截来源的记录（如 'policy'=策略拒绝，FR-006 可筛口径）。 */
  public List<ToolInvocationView> toolList(Instant from, Instant to, int limit, String blockedBy) {
    return toolInvocationRepository.findByCreatedAtBetween(from, to).stream()
        .filter(t -> blockedBy == null || blockedBy.equals(t.getBlockedBy()))
        .sorted(
            Comparator.comparing(
                ToolInvocation::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(limit)
        .map(ToolInvocationView::from)
        .toList();
  }

  private List<AuditGroupView> llmGroup(Instant from, Instant to, Function<LlmCall, String> keyFn) {
    Map<String, List<LlmCall>> grouped =
        llmCallRepository.findByCreatedAtBetween(from, to).stream()
            .collect(
                Collectors.groupingBy(
                    c -> {
                      String key = keyFn.apply(c);
                      return key == null ? UNASSIGNED : key;
                    }));
    return grouped.entrySet().stream()
        .map(
            e -> {
              List<LlmCall> cs = e.getValue();
              long successCount = cs.stream().filter(LlmCall::isSuccess).count();
              return new AuditGroupView(e.getKey(), cs.size(), successCount, sumCost(cs));
            })
        .sorted(Comparator.comparingLong(AuditGroupView::count).reversed())
        .toList();
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
