package io.oryxos.core.task;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local team-task run store. */
public final class InMemoryTeamTaskRunStore implements TeamTaskRunStore {

  private final ConcurrentHashMap<String, TeamTaskResult> byId = new ConcurrentHashMap<>();

  @Override
  public TeamTaskResult save(TeamTaskResult result) {
    Objects.requireNonNull(result, "result");
    if (result.id() == null || result.id().isBlank()) {
      throw new IllegalArgumentException("result.id must not be blank");
    }
    byId.put(result.id(), result);
    return result;
  }

  @Override
  public Optional<TeamTaskResult> find(String taskId) {
    if (taskId == null || taskId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byId.get(taskId.strip()));
  }
}
