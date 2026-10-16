package io.oryxos.storage;

import io.oryxos.core.cost.CostAttributionQuery;
import io.oryxos.core.cost.CostFacets;
import io.oryxos.core.cost.CostLedgerEntry;
import io.oryxos.core.cost.CostLedgerStore;
import io.oryxos.core.cost.CostSourceKind;
import java.time.Instant;
import java.util.List;

public class JpaCostLedgerStore implements CostLedgerStore {

  private final CostLedgerEntryRepository repository;

  public JpaCostLedgerStore(CostLedgerEntryRepository repository) {
    this.repository = repository;
  }

  @Override
  public CostLedgerEntry append(CostLedgerEntry entry) {
    CostLedgerEntryEntity e = new CostLedgerEntryEntity();
    e.setRunId(entry.runId());
    e.setTaskId(entry.taskId());
    e.setAgentName(entry.agentName());
    e.setTeamId(entry.teamId());
    e.setProvider(entry.provider());
    e.setModel(entry.model());
    e.setSourceKind(entry.sourceKind().name());
    e.setSourceRef(entry.sourceRef());
    e.setPromptTokens(entry.promptTokens());
    e.setCompletionTokens(entry.completionTokens());
    e.setTotalTokens(entry.totalTokens());
    e.setLlmCostMicros(entry.llmCostMicros());
    e.setToolCostMicros(entry.toolCostMicros());
    e.setLatencyMs(entry.latencyMs());
    e.setPriceVersion(entry.priceVersion());
    e.setSessionId(entry.sessionId());
    e.setTraceId(entry.traceId());
    e.setCreatedAt(entry.createdAt());
    return toDomain(repository.save(e));
  }

  @Override
  public List<CostLedgerEntry> find(CostAttributionQuery query) {
    CostAttributionQuery q = normalize(query);
    return repository
        .search(
            blank(q.runId()),
            blank(q.taskId()),
            blank(q.agentName()),
            blank(q.teamId()),
            blank(q.provider()),
            blank(q.model()))
        .stream()
        .map(JpaCostLedgerStore::toDomain)
        .filter(e -> inRange(e.createdAt(), q.from(), q.to()))
        .toList();
  }

  private static boolean inRange(
      java.time.Instant at, java.time.Instant from, java.time.Instant to) {
    if (from != null && (at == null || at.isBefore(from))) {
      return false;
    }
    return to == null || (at != null && at.isBefore(to));
  }

  /**
   * 开区间哨兵：{@code created_at} 非空（实体与两个 vendor 的迁移都是 NOT NULL），所以 [EPOCH, 9999) 覆盖全部行，末端的排他口径与 {@link
   * #inRange} 一致。传哨兵而不是传 null，是因为 Postgres 无法为「{@code ? is null}」里的 null 时间参数推断类型，会以 {@code could
   * not determine data type of parameter} 直接失败 —— 这条只有两库同套的契约用例拦得住（SQLite 不报错）。
   */
  private static final Instant OPEN_START = Instant.EPOCH;

  private static final Instant OPEN_END = Instant.parse("9999-12-31T23:59:59Z");

  private static Instant openStart(CostAttributionQuery q) {
    return q.from() == null ? OPEN_START : q.from();
  }

  private static Instant openEnd(CostAttributionQuery q) {
    return q.to() == null ? OPEN_END : q.to();
  }

  @Override
  public long sumLlmCostMicros(CostAttributionQuery query) {
    CostAttributionQuery q = normalize(query);
    return repository.sumLlmCostMicros(
        blank(q.runId()),
        blank(q.taskId()),
        blank(q.agentName()),
        blank(q.teamId()),
        blank(q.provider()),
        blank(q.model()),
        openStart(q),
        openEnd(q));
  }

  @Override
  public long sumToolCostMicros(CostAttributionQuery query) {
    CostAttributionQuery q = normalize(query);
    return repository.sumToolCostMicros(
        blank(q.runId()),
        blank(q.taskId()),
        blank(q.agentName()),
        blank(q.teamId()),
        blank(q.provider()),
        blank(q.model()),
        openStart(q),
        openEnd(q));
  }

  @Override
  public CostFacets facets() {
    return new CostFacets(
        repository.distinctAgents(),
        repository.distinctProviders(),
        repository.distinctModels(),
        repository.distinctTeams());
  }

  private static CostAttributionQuery normalize(CostAttributionQuery query) {
    return query == null ? new CostAttributionQuery(null, null, null, null, null, null) : query;
  }

  private static String blank(String v) {
    return v == null || v.isBlank() ? null : v;
  }

  private static CostLedgerEntry toDomain(CostLedgerEntryEntity e) {
    return new CostLedgerEntry(
        e.getId() == null ? 0L : e.getId(),
        e.getRunId(),
        e.getTaskId(),
        e.getAgentName(),
        e.getTeamId(),
        e.getProvider(),
        e.getModel(),
        CostSourceKind.valueOf(e.getSourceKind()),
        e.getSourceRef(),
        e.getPromptTokens(),
        e.getCompletionTokens(),
        e.getTotalTokens(),
        e.getLlmCostMicros(),
        e.getToolCostMicros(),
        e.getLatencyMs(),
        e.getPriceVersion(),
        e.getSessionId(),
        e.getTraceId(),
        e.getCreatedAt());
  }
}
