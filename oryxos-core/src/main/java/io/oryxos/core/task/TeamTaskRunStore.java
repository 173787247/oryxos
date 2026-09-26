package io.oryxos.core.task;

import java.util.Optional;

/** Persistence for Direction I team-task runs (in-memory fallback; JPA when storage available). */
public interface TeamTaskRunStore {

  TeamTaskResult save(TeamTaskResult result);

  Optional<TeamTaskResult> find(String taskId);
}
