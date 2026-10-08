package io.oryxos.storage;

import io.oryxos.core.cost.CostAttributionQuery;
import io.oryxos.core.cost.CostLedgerEntry;
import io.oryxos.core.cost.CostLedgerStore;
import io.oryxos.core.cost.CostSourceKind;
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
        .toList();
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
        blank(q.model()));
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
        blank(q.model()));
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
