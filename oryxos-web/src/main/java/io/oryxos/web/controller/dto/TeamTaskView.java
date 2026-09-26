package io.oryxos.web.controller.dto;

import io.oryxos.core.task.TeamTaskResult;
import java.util.List;

public record TeamTaskView(
    String id,
    String goal,
    String coordinator,
    String planRaw,
    List<WorkerView> workers,
    String summary) {

  public TeamTaskView {
    workers = workers == null ? List.of() : List.copyOf(workers);
  }

  public static TeamTaskView from(TeamTaskResult r) {
    List<WorkerView> workers =
        r.workers().stream()
            .map(w -> new WorkerView(w.agent(), w.message(), w.reply(), w.error()))
            .toList();
    return new TeamTaskView(r.id(), r.goal(), r.coordinator(), r.planRaw(), workers, r.summary());
  }

  public record WorkerView(String agent, String message, String reply, String error) {}
}
