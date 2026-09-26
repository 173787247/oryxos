package io.oryxos.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direction I: coordinator agent produces a JSON plan, then specialists run (bounded). Fan-out is
 * parallel by default (virtual threads); set parallel=false for sequential. No A2A; fail-loud on
 * empty plan / missing coordinator. Persists when a {@link TeamTaskRunStore} is provided.
 */
public final class TeamTaskOrchestrator {

  private static final Pattern JSON_BLOCK =
      Pattern.compile("\\{[\\s\\S]*\"subtasks\"[\\s\\S]*\\}", Pattern.MULTILINE);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TeamAgentRunner runner;
  private final String defaultCoordinator;
  private final int maxSubtasks;
  private final boolean parallel;
  private final TeamTaskRunStore runStore;
  private final TeamAgentCatalog agentCatalog;

  public TeamTaskOrchestrator(TeamAgentRunner runner, String defaultCoordinator, int maxSubtasks) {
    this(runner, defaultCoordinator, maxSubtasks, true, null, null);
  }

  public TeamTaskOrchestrator(
      TeamAgentRunner runner,
      String defaultCoordinator,
      int maxSubtasks,
      TeamTaskRunStore runStore,
      TeamAgentCatalog agentCatalog) {
    this(runner, defaultCoordinator, maxSubtasks, true, runStore, agentCatalog);
  }

  public TeamTaskOrchestrator(
      TeamAgentRunner runner,
      String defaultCoordinator,
      int maxSubtasks,
      boolean parallel,
      TeamTaskRunStore runStore,
      TeamAgentCatalog agentCatalog) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.defaultCoordinator =
        defaultCoordinator == null || defaultCoordinator.isBlank()
            ? "coordinator"
            : defaultCoordinator.strip();
    this.maxSubtasks = maxSubtasks <= 0 ? 4 : Math.min(maxSubtasks, 16);
    this.parallel = parallel;
    this.runStore = runStore;
    this.agentCatalog = agentCatalog;
  }

  public TeamTaskResult run(String goal) {
    return run(goal, null);
  }

  public TeamTaskResult run(String goal, String coordinatorOverride) {
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal must not be blank");
    }
    String taskId = UUID.randomUUID().toString();
    String coordinator =
        coordinatorOverride == null || coordinatorOverride.isBlank()
            ? defaultCoordinator
            : coordinatorOverride.strip();
    String planPrompt =
        """
        You are the team coordinator. For the user goal below, reply with ONLY a JSON object:
        {"subtasks":[{"agent":"<existing-agent-name>","message":"<concrete subtask>"}]}
        At most MAX_SUBTASKS subtasks. Use real agent directory names the platform already has.
        Known agents:
        KNOWN_AGENTS
        Goal:
        GOAL_TEXT
        """
            .replace("MAX_SUBTASKS", Integer.toString(maxSubtasks))
            .replace("KNOWN_AGENTS", formatKnownAgents())
            .replace("GOAL_TEXT", goal.strip());
    String planRaw = runner.run(coordinator, planPrompt);
    TeamTaskPlan plan = parsePlan(planRaw);
    TeamTaskResult.Builder out =
        TeamTaskResult.builder()
            .id(taskId)
            .goal(goal.strip())
            .coordinator(coordinator)
            .planRaw(planRaw);
    List<TeamTaskPlan.SubTask> work = new ArrayList<>();
    int n = 0;
    for (TeamTaskPlan.SubTask sub : plan.subtasks()) {
      if (n >= maxSubtasks) {
        break;
      }
      n++;
      work.add(sub);
    }
    List<TeamTaskResult.WorkerResult> workerResults =
        parallel ? runParallel(work) : runSequential(work);
    List<String> resultBlocks = new ArrayList<>();
    for (TeamTaskResult.WorkerResult wr : workerResults) {
      out.addWorker(wr);
      if (wr.failed()) {
        resultBlocks.add(wr.agent() + " FAILED: " + wr.error());
      } else {
        resultBlocks.add(wr.agent() + ": " + wr.reply());
      }
    }
    String summaryPrompt =
        "Summarize the team delivery for the goal.\nGoal: "
            + goal.strip()
            + "\nWorker results:\n"
            + String.join("\n---\n", resultBlocks);
    String summary = runner.run(coordinator, summaryPrompt);
    TeamTaskResult result = out.summary(summary).build();
    if (runStore != null) {
      runStore.save(result);
    }
    return result;
  }

  public Optional<TeamTaskResult> find(String taskId) {
    if (runStore == null) {
      return Optional.empty();
    }
    return runStore.find(taskId);
  }

  private String formatKnownAgents() {
    if (agentCatalog == null) {
      return "(none listed — use agent names that exist in this workspace)";
    }
    List<String> names = agentCatalog.names();
    if (names == null || names.isEmpty()) {
      return "(none listed — use agent names that exist in this workspace)";
    }
    return String.join(", ", names);
  }

  private List<TeamTaskResult.WorkerResult> runSequential(List<TeamTaskPlan.SubTask> work) {
    List<TeamTaskResult.WorkerResult> out = new ArrayList<>(work.size());
    for (TeamTaskPlan.SubTask sub : work) {
      out.add(runOne(sub));
    }
    return out;
  }

  @SuppressWarnings("PMD.ThreadPoolCreationRule")
  private List<TeamTaskResult.WorkerResult> runParallel(List<TeamTaskPlan.SubTask> work) {
    if (work.isEmpty()) {
      return List.of();
    }
    if (work.size() == 1) {
      return List.of(runOne(work.get(0)));
    }
    List<TeamTaskResult.WorkerResult> out = new ArrayList<>(work.size());
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<TeamTaskResult.WorkerResult>> futures = new ArrayList<>(work.size());
      for (TeamTaskPlan.SubTask sub : work) {
        futures.add(pool.submit(() -> runOne(sub)));
      }
      for (int i = 0; i < futures.size(); i++) {
        TeamTaskPlan.SubTask sub = work.get(i);
        try {
          out.add(futures.get(i).get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          out.add(
              new TeamTaskResult.WorkerResult(
                  sub.agent(), sub.message(), "", "interrupted: " + e.getClass().getSimpleName()));
        } catch (ExecutionException e) {
          Throwable c = e.getCause() == null ? e : e.getCause();
          String err = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
          out.add(new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), "", err));
        }
      }
    }
    return out;
  }

  private TeamTaskResult.WorkerResult runOne(TeamTaskPlan.SubTask sub) {
    try {
      String reply = runner.run(sub.agent(), sub.message());
      return new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), reply, null);
    } catch (RuntimeException e) {
      String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), "", err);
    }
  }

  static TeamTaskPlan parsePlan(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("coordinator returned empty plan");
    }
    String json = extractJson(raw);
    try {
      JsonNode root = MAPPER.readTree(json);
      JsonNode arr = root.get("subtasks");
      if (arr == null || !arr.isArray() || arr.isEmpty()) {
        throw new IllegalStateException("plan missing non-empty subtasks array");
      }
      List<TeamTaskPlan.SubTask> list = new ArrayList<>();
      for (JsonNode n : arr) {
        String agent = text(n, "agent");
        String message = text(n, "message");
        if (agent.isBlank()) {
          continue;
        }
        list.add(new TeamTaskPlan.SubTask(agent, message));
      }
      if (list.isEmpty()) {
        throw new IllegalStateException("plan subtasks had no usable agent entries");
      }
      return new TeamTaskPlan(list);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "failed to parse coordinator plan JSON: " + e.getMessage(), e);
    }
  }

  private static String extractJson(String raw) {
    String t = raw.strip();
    if (t.startsWith("{")) {
      return t;
    }
    Matcher m = JSON_BLOCK.matcher(t);
    if (m.find()) {
      return m.group();
    }
    throw new IllegalStateException("coordinator reply contained no JSON plan object");
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? "" : v.asText("");
  }
}
