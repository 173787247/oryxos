package io.oryxos.core.flow;

import io.oryxos.core.task.TeamTaskResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Flow bridge for Direction I: {@link FlowNodeType#TEAM_TASK} nodes invoke a team-task runner
 * (typically {@code TeamTaskOrchestrator#run}). Other node types fall through to the delegate.
 *
 * <p>{@code node.ref} is an optional coordinator override. Goal is taken from input ports {@code
 * goal}, {@code message}, or {@code text} (first non-blank).
 */
public final class TeamTaskAwareFlowNodeHandler implements FlowNodeHandler {

  private final FlowNodeHandler delegate;

  /** (goal, coordinatorOrNull) → TeamTaskResult */
  private final BiFunction<String, String, TeamTaskResult> runner;

  public TeamTaskAwareFlowNodeHandler(
      FlowNodeHandler delegate, BiFunction<String, String, TeamTaskResult> runner) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.runner = runner;
  }

  public TeamTaskAwareFlowNodeHandler(FlowNodeHandler delegate) {
    this(delegate, null);
  }

  @Override
  public FlowNodeOutcome execute(FlowNode node, Map<String, Object> inputs, FlowRun run) {
    Objects.requireNonNull(node, "node");
    if (node.type() != FlowNodeType.TEAM_TASK || runner == null) {
      return delegate.execute(node, inputs, run);
    }
    String goal = extractGoal(inputs);
    if (goal.isBlank()) {
      return FlowNodeOutcome.failed("TEAM_TASK node missing goal (input goal/message/text)");
    }
    String coordinator = node.ref();
    if (coordinator != null && coordinator.isBlank()) {
      coordinator = null;
    }
    try {
      TeamTaskResult result = runner.apply(goal.strip(), coordinator);
      if (result == null) {
        return FlowNodeOutcome.failed("TEAM_TASK runner returned null");
      }
      return FlowNodeOutcome.succeeded(mapOutputs(node, result, inputs));
    } catch (RuntimeException e) {
      String msg = e.getMessage();
      return FlowNodeOutcome.failed(
          msg == null || msg.isBlank()
              ? "TEAM_TASK run failed: " + e.getClass().getSimpleName()
              : msg);
    }
  }

  static String extractGoal(Map<String, Object> inputs) {
    Map<String, Object> in = inputs == null ? Map.of() : inputs;
    for (String key : new String[] {"goal", "message", "text"}) {
      Object v = in.get(key);
      if (v != null && !String.valueOf(v).isBlank()) {
        return String.valueOf(v).strip();
      }
    }
    return "";
  }

  static Map<String, Object> mapOutputs(
      FlowNode node, TeamTaskResult result, Map<String, Object> inputs) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> in = inputs == null ? Map.of() : inputs;
    String summary = result.summary() == null ? "" : result.summary();
    Map<String, FlowPort> ports = node.outputs();
    if (ports.isEmpty()) {
      out.put("summary", summary);
      out.put("message", summary);
      out.put("teamTaskId", result.id());
      out.put("plan", result.planRaw() == null ? "" : result.planRaw());
      return out;
    }
    for (String port : ports.keySet()) {
      switch (port) {
        case "summary", "message", "text", "result" -> out.put(port, summary);
        case "teamTaskId", "id" -> out.put(port, result.id());
        case "plan", "planRaw" -> out.put(port, result.planRaw() == null ? "" : result.planRaw());
        case "coordinator" -> out.put(port, result.coordinator());
        case "goal" -> out.put(port, result.goal());
        default -> out.put(port, in.getOrDefault(port, null));
      }
    }
    return out;
  }
}
