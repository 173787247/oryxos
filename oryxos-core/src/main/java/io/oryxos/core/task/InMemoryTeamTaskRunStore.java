package io.oryxos.core.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local team-task run store. */
public final class InMemoryTeamTaskRunStore implements TeamTaskRunStore {

  private final ConcurrentHashMap<String, TeamTaskResult> byId = new ConcurrentHashMap<>();

  /** Monotonic insert order for newest-first listing (id alone is UUID). */
  private final ConcurrentHashMap<String, Long> seqById = new ConcurrentHashMap<>();

  private final AtomicLong seq = new AtomicLong();

  @Override
  public TeamTaskResult save(TeamTaskResult result) {
    Objects.requireNonNull(result, "result");
    if (result.id() == null || result.id().isBlank()) {
      throw new IllegalArgumentException("result.id must not be blank");
    }
    byId.put(result.id(), result);
    seqById.putIfAbsent(result.id(), seq.incrementAndGet());
    return result;
  }

  @Override
  public Optional<TeamTaskResult> find(String taskId) {
    if (taskId == null || taskId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byId.get(taskId.strip()));
  }

  @Override
  public List<TeamTaskResult> listRecent(int limit) {
    int n = normalizeLimit(limit);
    List<String> ids = new ArrayList<>(byId.keySet());
    ids.sort(Comparator.comparingLong((String id) -> seqById.getOrDefault(id, 0L)).reversed());
    List<TeamTaskResult> out = new ArrayList<>(Math.min(n, ids.size()));
    for (int i = 0; i < ids.size() && out.size() < n; i++) {
      TeamTaskResult r = byId.get(ids.get(i));
      if (r != null) {
        out.add(r);
      }
    }
    return List.copyOf(out);
  }

  static int normalizeLimit(int limit) {
    if (limit <= 0) {
      return 20;
    }
    return Math.min(limit, 100);
  }
}
