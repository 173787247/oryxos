package io.oryxos.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Result of one Direction I MVP team run. */
public record TeamTaskResult(
    String goal, String coordinator, String planRaw, List<WorkerResult> workers, String summary) {

  public TeamTaskResult {
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(coordinator, "coordinator");
    workers = workers == null ? List.of() : List.copyOf(workers);
  }

  public record WorkerResult(String agent, String message, String reply, String error) {
    public WorkerResult {
      agent = agent == null ? "" : agent;
      message = message == null ? "" : message;
      reply = reply == null ? "" : reply;
      error = error == null || error.isBlank() ? null : error;
    }

    public boolean failed() {
      return error != null;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String goal;
    private String coordinator;
    private String planRaw;
    private final List<WorkerResult> workers = new ArrayList<>();
    private String summary;

    public Builder goal(String goal) {
      this.goal = goal;
      return this;
    }

    public Builder coordinator(String coordinator) {
      this.coordinator = coordinator;
      return this;
    }

    public Builder planRaw(String planRaw) {
      this.planRaw = planRaw;
      return this;
    }

    public Builder addWorker(WorkerResult w) {
      workers.add(w);
      return this;
    }

    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    public TeamTaskResult build() {
      return new TeamTaskResult(goal, coordinator, planRaw, workers, summary);
    }
  }
}
