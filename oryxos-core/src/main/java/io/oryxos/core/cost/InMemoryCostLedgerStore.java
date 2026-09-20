package io.oryxos.core.cost;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory ledger for unit tests and flag-off fallback. */
public final class InMemoryCostLedgerStore implements CostLedgerStore {

  private final AtomicLong seq = new AtomicLong();
  private final CopyOnWriteArrayList<CostLedgerEntry> rows = new CopyOnWriteArrayList<>();

  @Override
  public CostLedgerEntry append(CostLedgerEntry entry) {
    long id = entry.id() > 0 ? entry.id() : seq.incrementAndGet();
    Instant at = entry.createdAt() == null ? Instant.now() : entry.createdAt();
    CostLedgerEntry saved =
        new CostLedgerEntry(
            id,
            entry.runId(),
            entry.taskId(),
            entry.agentName(),
            entry.teamId(),
            entry.provider(),
            entry.model(),
            entry.sourceKind(),
            entry.sourceRef(),
            entry.promptTokens(),
            entry.completionTokens(),
            entry.totalTokens(),
            entry.llmCostMicros(),
            entry.toolCostMicros(),
            entry.latencyMs(),
            entry.priceVersion(),
            entry.sessionId(),
            entry.traceId(),
            at);
    rows.add(saved);
    return saved;
  }

  @Override
  public List<CostLedgerEntry> find(CostAttributionQuery query) {
    return rows.stream().filter(e -> matches(e, query)).toList();
  }

  @Override
  public long sumLlmCostMicros(CostAttributionQuery query) {
    return find(query).stream().mapToLong(CostLedgerEntry::llmCostMicros).sum();
  }

  @Override
  public long sumToolCostMicros(CostAttributionQuery query) {
    return find(query).stream().mapToLong(CostLedgerEntry::toolCostMicros).sum();
  }

  public void clear() {
    rows.clear();
    seq.set(0);
  }

  public List<CostLedgerEntry> all() {
    return new ArrayList<>(rows);
  }

  private static boolean matches(CostLedgerEntry e, CostAttributionQuery q) {
    if (q == null) {
      return true;
    }
    return eq(q.runId(), e.runId())
        && eq(q.taskId(), e.taskId())
        && eq(q.agentName(), e.agentName())
        && eq(q.teamId(), e.teamId())
        && eq(q.provider(), e.provider())
        && eq(q.model(), e.model());
  }

  private static boolean eq(String filter, String value) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return Objects.equals(filter, value);
  }
}
