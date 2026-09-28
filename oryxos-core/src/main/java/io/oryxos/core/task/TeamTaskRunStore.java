package io.oryxos.core.task;

import java.util.List;
import java.util.Optional;

/** Persistence for Direction I team-task runs (in-memory fallback; JPA when storage available). */
public interface TeamTaskRunStore {

  TeamTaskResult save(TeamTaskResult result);

  Optional<TeamTaskResult> find(String taskId);

  /** Newest-first recent runs. {@code limit} ≤ 0 uses default 20; capped at 100. */
  List<TeamTaskResult> listRecent(int limit);
}
