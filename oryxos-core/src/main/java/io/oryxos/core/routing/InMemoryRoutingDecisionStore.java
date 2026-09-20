package io.oryxos.core.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Bounded in-memory decision log for explainability API (#477 thin cut). */
public final class InMemoryRoutingDecisionStore implements RoutingDecisionStore {

  private final int capacity;
  private final Deque<RoutingDecision> q = new ArrayDeque<>();

  public InMemoryRoutingDecisionStore(int capacity) {
    this.capacity = Math.max(16, capacity);
  }

  @Override
  public synchronized void append(RoutingDecision decision) {
    if (decision == null) {
      return;
    }
    q.addLast(decision);
    while (q.size() > capacity) {
      q.removeFirst();
    }
  }

  @Override
  public synchronized List<RoutingDecision> findByRunId(String runId) {
    if (runId == null || runId.isBlank()) {
      return List.of();
    }
    List<RoutingDecision> out = new ArrayList<>();
    for (RoutingDecision d : q) {
      if (Objects.equals(runId, d.runId())) {
        out.add(d);
      }
    }
    return List.copyOf(out);
  }

  @Override
  public synchronized List<RoutingDecision> recent(int limit) {
    int n = Math.max(0, limit);
    List<RoutingDecision> all = new ArrayList<>(q);
    if (all.size() <= n) {
      return List.copyOf(all);
    }
    return List.copyOf(all.subList(all.size() - n, all.size()));
  }
}
