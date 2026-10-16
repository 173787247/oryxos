package io.oryxos.core.cost;

import io.oryxos.core.agent.TraceContext;
import io.oryxos.core.provider.Usage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Task-level cost ledger, attribution query, budget check, and audit reconcile (#476).
 *
 * <p>When {@code oryxos.cost.enabled=false}, record/query/reconcile are no-ops / empty; enforcement
 * never blocks.
 */
public final class CostLedgerService {

  private final CostProperties properties;
  private final CostLedgerStore store;
  private final AuditLlmCostSource auditCosts;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-shared configuration + store beans by design.")
  public CostLedgerService(
      CostProperties properties, CostLedgerStore store, AuditLlmCostSource auditCosts) {
    this.properties = properties == null ? new CostProperties() : properties;
    this.store = store == null ? new InMemoryCostLedgerStore() : store;
    this.auditCosts = auditCosts == null ? AuditLlmCostSource.NOOP : auditCosts;
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  public boolean isEnforcementEnabled() {
    return properties.isEnabled() && properties.isEnforcementEnabled();
  }

  public CostLedgerEntry recordLlm(
      String sessionId,
      String agentName,
      String provider,
      String model,
      Usage usage,
      Long costMicros,
      Long priceVersion,
      long latencyMs) {
    if (!properties.isEnabled()) {
      return null;
    }
    long llm = costMicros == null ? 0L : Math.max(0L, costMicros);
    return store.append(
        baseEntry(
            CostSourceKind.LLM,
            provider + "/" + model,
            sessionId,
            agentName,
            provider,
            model,
            usage,
            llm,
            0L,
            priceVersion,
            latencyMs));
  }

  public CostLedgerEntry recordTool(
      String sessionId, String agentName, String toolName, long latencyMs) {
    if (!properties.isEnabled()) {
      return null;
    }
    long tool = properties.toolCostMicros(toolName);
    return store.append(
        baseEntry(
            CostSourceKind.TOOL,
            toolName,
            sessionId,
            agentName,
            null,
            null,
            null,
            0L,
            tool,
            null,
            latencyMs));
  }

  public CostAttributionSummary query(CostAttributionQuery query) {
    if (!properties.isEnabled()) {
      return emptySummary();
    }
    List<CostLedgerEntry> entries =
        store.find(
            query == null ? new CostAttributionQuery(null, null, null, null, null, null) : query);
    long llm = 0;
    long tool = 0;
    long latency = 0;
    long prompt = 0;
    long completion = 0;
    long llmCalls = 0;
    long toolCalls = 0;
    LinkedHashSet<Long> versions = new LinkedHashSet<>();
    for (CostLedgerEntry e : entries) {
      llm += e.llmCostMicros();
      tool += e.toolCostMicros();
      latency += e.latencyMs();
      if (e.sourceKind() == CostSourceKind.LLM) {
        llmCalls++;
      } else if (e.sourceKind() == CostSourceKind.TOOL) {
        toolCalls++;
      }
      if (e.promptTokens() != null) {
        prompt += e.promptTokens();
      }
      if (e.completionTokens() != null) {
        completion += e.completionTokens();
      }
      if (e.priceVersion() != null) {
        versions.add(e.priceVersion());
      }
    }
    return new CostAttributionSummary(
        List.copyOf(entries),
        llm,
        tool,
        llm + tool,
        latency,
        prompt,
        completion,
        List.copyOf(versions),
        llmCalls,
        toolCalls);
  }

  /** 最大日期跨度（053 趋势图保护）。 */
  public static final int MAX_DAILY_RANGE_DAYS = 92;

  /**
   * 按天 LLM 成本序列（053 趋势）；服务端本地时区分天、范围内补零。query 的 from/to 必填，跨度 ≤ {@link #MAX_DAILY_RANGE_DAYS}
   * 天。未开启返回空。
   */
  public List<DailyCostPoint> dailyLlmCost(CostAttributionQuery query) {
    if (!properties.isEnabled()) {
      return List.of();
    }
    if (query == null || query.from() == null || query.to() == null) {
      throw new IllegalArgumentException("按天趋势需要 from 与 to 时间参数");
    }
    if (!query.to().isAfter(query.from())) {
      throw new IllegalArgumentException("to 必须晚于 from");
    }
    ZoneId zone = ZoneId.systemDefault();
    LocalDate startDay = query.from().atZone(zone).toLocalDate();
    LocalDate endDay = query.to().minusMillis(1).atZone(zone).toLocalDate();
    long days = ChronoUnit.DAYS.between(startDay, endDay) + 1;
    if (days > MAX_DAILY_RANGE_DAYS) {
      throw new IllegalArgumentException("日期范围不能超过 " + MAX_DAILY_RANGE_DAYS + " 天");
    }
    Map<LocalDate, Long> sums = new HashMap<>();
    for (CostLedgerEntry e : store.find(query)) {
      if (e.sourceKind() != CostSourceKind.LLM || e.createdAt() == null) {
        continue;
      }
      LocalDate day = e.createdAt().atZone(zone).toLocalDate();
      sums.merge(day, e.llmCostMicros(), Long::sum);
    }
    List<DailyCostPoint> out = new ArrayList<>();
    for (LocalDate d = startDay; !d.isAfter(endDay); d = d.plusDays(1)) {
      out.add(new DailyCostPoint(d.toString(), sums.getOrDefault(d, 0L)));
    }
    return out;
  }

  /**
   * 工具调用归集（053）：按 agent 统计工具调用次数（sourceKind=TOOL）；{@code byTool=true}
   * 时再按工具名细分。按次数降序、agent/工具名稳定排序。未开启返回空。
   */
  public List<ToolUsageEntry> toolUsage(CostAttributionQuery query, boolean byTool) {
    if (!properties.isEnabled()) {
      return List.of();
    }
    Map<String, Long> counts = new HashMap<>();
    Map<String, String[]> meta = new HashMap<>();
    for (CostLedgerEntry e : store.find(query)) {
      if (e.sourceKind() != CostSourceKind.TOOL) {
        continue;
      }
      String agent = e.agentName() == null ? "" : e.agentName();
      String tool = byTool ? (e.sourceRef() == null ? "" : e.sourceRef()) : null;
      String key = agent + "\u0000" + (tool == null ? "" : tool);
      counts.merge(key, 1L, Long::sum);
      meta.putIfAbsent(key, new String[] {agent, tool});
    }
    return counts.entrySet().stream()
        .map(
            en ->
                new ToolUsageEntry(
                    meta.get(en.getKey())[0], meta.get(en.getKey())[1], en.getValue()))
        .sorted(
            (a, b) -> {
              int c = Long.compare(b.callCount(), a.callCount());
              if (c != 0) {
                return c;
              }
              c = a.agentName().compareTo(b.agentName());
              if (c != 0) {
                return c;
              }
              return String.valueOf(a.tool()).compareTo(String.valueOf(b.tool()));
            })
        .toList();
  }

  /** 筛选器候选值（053）；未开启返回空。 */
  public CostFacets facets() {
    if (!properties.isEnabled()) {
      return CostFacets.empty();
    }
    return store.facets();
  }

  public BudgetDecision checkBudget(String agentName, String teamId, String model, String runId) {
    if (!isEnforcementEnabled()) {
      return BudgetDecision.allow();
    }
    for (CostProperties.BudgetRule rule : properties.getBudgets()) {
      if (rule == null || rule.getLimitMicros() <= 0) {
        continue;
      }
      if (!matchesRule(rule, agentName, teamId, model, runId)) {
        continue;
      }
      CostAttributionQuery q = queryForRule(rule, agentName, teamId, model, runId);
      long spent = store.sumLlmCostMicros(q) + store.sumToolCostMicros(q);
      if (spent >= rule.getLimitMicros()) {
        String reason =
            "budget exceeded: rule="
                + (rule.getId().isBlank() ? rule.getScope() : rule.getId())
                + " spent="
                + spent
                + " limit="
                + rule.getLimitMicros();
        if (properties.getOverBudgetAction() == OverBudgetAction.DEGRADE) {
          return BudgetDecision.degrade(
              reason, properties.getDegradeProvider(), properties.getDegradeModel());
        }
        return BudgetDecision.block(reason);
      }
    }
    return BudgetDecision.allow();
  }

  public CostReconcileResult reconcile(String runId) {
    if (!properties.isEnabled()) {
      return new CostReconcileResult(runId, 0, 0, 0, true, "cost ledger disabled");
    }
    String id = runId == null || runId.isBlank() ? TraceContext.current() : runId;
    if (id == null || id.isBlank()) {
      return new CostReconcileResult(null, 0, 0, 0, false, "runId required");
    }
    CostAttributionQuery q = new CostAttributionQuery(id, null, null, null, null, null);
    long ledgerLlm = store.sumLlmCostMicros(q);
    long ledgerTool = store.sumToolCostMicros(q);
    long audit = auditCosts.sumCostMicrosByTraceId(id);
    boolean matched = ledgerLlm == audit;
    String detail =
        matched
            ? "ledger llm matches audit llm_calls"
            : "mismatch ledgerLlm=" + ledgerLlm + " auditLlm=" + audit;
    return new CostReconcileResult(id, ledgerLlm, ledgerTool, audit, matched, detail);
  }

  private CostLedgerEntry baseEntry(
      CostSourceKind kind,
      String sourceRef,
      String sessionId,
      String agentName,
      String provider,
      String model,
      Usage usage,
      long llm,
      long tool,
      Long priceVersion,
      long latencyMs) {
    CostContext.State ctx = CostContext.current();
    String trace = TraceContext.current();
    String runId = ctx != null && ctx.runId() != null ? ctx.runId() : trace;
    String taskId = ctx != null ? ctx.taskId() : null;
    if (taskId == null) {
      taskId = sessionId;
    }
    String teamId = ctx != null ? ctx.teamId() : null;
    Integer prompt = usage == null ? null : usage.promptTokens();
    Integer completion = usage == null ? null : usage.completionTokens();
    Integer total = usage == null ? null : usage.totalTokens();
    return new CostLedgerEntry(
        0L,
        runId,
        taskId,
        agentName,
        teamId,
        provider,
        model,
        kind,
        sourceRef,
        prompt,
        completion,
        total,
        llm,
        tool,
        Math.max(0L, latencyMs),
        priceVersion,
        sessionId,
        trace,
        Instant.now());
  }

  private static boolean matchesRule(
      CostProperties.BudgetRule rule, String agentName, String teamId, String model, String runId) {
    String key = rule.getKey();
    if (key == null || key.isBlank()) {
      return true;
    }
    return switch (rule.getScope()) {
      case RUN -> Objects.equals(key, runId);
      case AGENT -> Objects.equals(key, agentName);
      case TEAM -> Objects.equals(key, teamId);
      case MODEL -> Objects.equals(key, model);
    };
  }

  private static CostAttributionQuery queryForRule(
      CostProperties.BudgetRule rule, String agentName, String teamId, String model, String runId) {
    return switch (rule.getScope()) {
      case RUN -> new CostAttributionQuery(runId, null, null, null, null, null);
      case AGENT -> new CostAttributionQuery(null, null, agentName, null, null, null);
      case TEAM -> new CostAttributionQuery(null, null, null, teamId, null, null);
      case MODEL -> new CostAttributionQuery(null, null, null, null, null, model);
    };
  }

  private static CostAttributionSummary emptySummary() {
    return new CostAttributionSummary(List.of(), 0, 0, 0, 0, 0, 0, List.of(), 0, 0);
  }
}
