package io.oryxos.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direction I MVP: coordinator agent produces a JSON plan, then specialists run in sequence
 * (bounded). No A2A; fail-loud on empty plan / missing coordinator.
 */
public final class TeamTaskOrchestrator {

  private static final Pattern JSON_BLOCK =
      Pattern.compile("\\{[\\s\\S]*\"subtasks\"[\\s\\S]*\\}", Pattern.MULTILINE);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TeamAgentRunner runner;
  private final String defaultCoordinator;
  private final int maxSubtasks;

  public TeamTaskOrchestrator(TeamAgentRunner runner, String defaultCoordinator, int maxSubtasks) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.defaultCoordinator =
        defaultCoordinator == null || defaultCoordinator.isBlank()
            ? "coordinator"
            : defaultCoordinator.strip();
    this.maxSubtasks = maxSubtasks <= 0 ? 4 : Math.min(maxSubtasks, 16);
  }

  public TeamTaskResult run(String goal) {
    return run(goal, null);
  }

  public TeamTaskResult run(String goal, String coordinatorOverride) {
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal must not be blank");
    }
    String coordinator =
        coordinatorOverride == null || coordinatorOverride.isBlank()
            ? defaultCoordinator
            : coordinatorOverride.strip();
    String planPrompt =
        """
        You are the team coordinator. For the user goal below, reply with ONLY a JSON object:
        {"subtasks":[{"agent":"<existing-agent-name>","message":"<concrete subtask>"}]}
        At most MAX_SUBTASKS subtasks. Use real agent directory names the platform already has.
        Goal:
        GOAL_TEXT
        """
            .replace("MAX_SUBTASKS", Integer.toString(maxSubtasks))
            .replace("GOAL_TEXT", goal.strip());
    String planRaw = runner.run(coordinator, planPrompt);
    TeamTaskPlan plan = parsePlan(planRaw);
    TeamTaskResult.Builder out =
        TeamTaskResult.builder().goal(goal.strip()).coordinator(coordinator).planRaw(planRaw);
    List<String> resultBlocks = new ArrayList<>();
    int n = 0;
    for (TeamTaskPlan.SubTask sub : plan.subtasks()) {
      if (n >= maxSubtasks) {
        break;
      }
      n++;
      try {
        String reply = runner.run(sub.agent(), sub.message());
        out.addWorker(new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), reply, null));
        resultBlocks.add(sub.agent() + ": " + reply);
      } catch (RuntimeException e) {
        String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        out.addWorker(new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), "", err));
        resultBlocks.add(sub.agent() + " FAILED: " + err);
      }
    }
    String summaryPrompt =
        "Summarize the team delivery for the goal.\nGoal: "
            + goal.strip()
            + "\nWorker results:\n"
            + String.join("\n---\n", resultBlocks);
    String summary = runner.run(coordinator, summaryPrompt);
    return out.summary(summary).build();
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
