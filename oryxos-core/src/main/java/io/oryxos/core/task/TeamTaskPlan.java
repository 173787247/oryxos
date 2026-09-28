package io.oryxos.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Parsed coordinator plan: bounded list of specialist subtasks. */
public record TeamTaskPlan(List<SubTask> subtasks) {

  public TeamTaskPlan {
    subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
  }

  /**
   * @param remote optional peer base URL for cross-node A2A; blank = local
   * @param after agent names that must finish before this subtask starts (wave scheduling)
   */
  public record SubTask(String agent, String message, String remote, List<String> after) {
    public SubTask(String agent, String message) {
      this(agent, message, "", List.of());
    }

    public SubTask(String agent, String message, String remote) {
      this(agent, message, remote, List.of());
    }

    public SubTask {
      agent = Objects.requireNonNull(agent, "agent").strip();
      message = message == null ? "" : message.strip();
      remote = remote == null ? "" : remote.strip().replaceAll("/+$", "");
      if (agent.isEmpty()) {
        throw new IllegalArgumentException("subtask agent must not be blank");
      }
      if (after == null || after.isEmpty()) {
        after = List.of();
      } else {
        List<String> cleaned = new ArrayList<>();
        for (String a : after) {
          if (a == null || a.isBlank()) {
            continue;
          }
          cleaned.add(a.strip());
        }
        after = List.copyOf(cleaned);
      }
    }

    public boolean hasRemote() {
      return !remote.isBlank();
    }

    public boolean hasAfter() {
      return !after.isEmpty();
    }
  }
}
