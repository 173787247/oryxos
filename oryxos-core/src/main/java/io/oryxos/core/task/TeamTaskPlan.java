package io.oryxos.core.task;

import java.util.List;
import java.util.Objects;

/** Parsed coordinator plan: bounded list of specialist subtasks. */
public record TeamTaskPlan(List<SubTask> subtasks) {

  public TeamTaskPlan {
    subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
  }

  public record SubTask(String agent, String message) {
    public SubTask {
      agent = Objects.requireNonNull(agent, "agent").strip();
      message = message == null ? "" : message.strip();
      if (agent.isEmpty()) {
        throw new IllegalArgumentException("subtask agent must not be blank");
      }
    }
  }
}
