package io.oryxos.tool.builtin;

import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.flow.FlowAgentRunner;
import io.oryxos.core.profile.Profile;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Same-process sub-agent handoff via {@link FlowAgentRunner} ({@code processStateless}). Not A2A.
 * Restores outer {@link ProfileContext} / {@link ToolExecutionContext} after the nested run.
 */
public class DelegateAgentTools {

  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
  private static final int MAX_REPLY_CHARS = 100_000;

  private final FlowAgentRunner runner;
  private final boolean enabled;
  private final int maxDepth;

  public DelegateAgentTools(FlowAgentRunner runner, boolean enabled, int maxDepth) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.enabled = enabled;
    this.maxDepth = maxDepth <= 0 ? 3 : Math.min(maxDepth, 8);
  }

  @Tool(
      name = "delegate_agent",
      description =
          "Hand a subtask to another Agent in-process (isolated stateless session). "
              + "Not for self-delegation; nest depth is capped. Does not use A2A network protocol.")
  public String delegateAgent(
      @ToolParam(description = "Target agent / profile directory name") String agent,
      @ToolParam(description = "Concrete subtask message for the target agent") String message) {
    if (!enabled) {
      throw new IllegalStateException(
          "delegate_agent is disabled; set oryxos.tool.delegate-agent.enabled=true");
    }
    if (agent == null || agent.isBlank()) {
      throw new IllegalArgumentException("agent must not be blank");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    String target = agent.strip();
    String caller = currentCaller();
    if (caller != null && caller.equals(target)) {
      throw new IllegalArgumentException(
          "delegate_agent refuses self-delegation to '" + target + "'");
    }
    int depth = DEPTH.get();
    if (depth >= maxDepth) {
      throw new IllegalStateException("delegate_agent nest depth exceeded (max " + maxDepth + ")");
    }

    Profile previousProfile = ProfileContext.current();
    String previousAgent = ToolExecutionContext.agentName();
    String previousBackend = ToolExecutionContext.executionBackend();
    // containerId is lazy; re-bind a supplier that returns the captured value (may be null).
    String previousContainerId = ToolExecutionContext.containerId();
    Supplier<String> previousContainerSupplier =
        previousBackend == null ? null : () -> previousContainerId;

    DEPTH.set(depth + 1);
    try {
      String reply = runner.run(target, message.strip());
      if (reply == null) {
        return "";
      }
      if (reply.length() > MAX_REPLY_CHARS) {
        return reply.substring(0, MAX_REPLY_CHARS) + "\n...[delegate_agent truncated]";
      }
      return reply;
    } finally {
      DEPTH.set(depth);
      if (depth == 0) {
        DEPTH.remove();
      }
      restoreProfile(previousProfile);
      restoreToolExecution(previousAgent, previousBackend, previousContainerSupplier);
    }
  }

  private static String currentCaller() {
    String fromTool = ToolExecutionContext.agentName();
    if (fromTool != null && !fromTool.isBlank()) {
      return fromTool.strip();
    }
    Profile p = ProfileContext.current();
    return p == null ? null : p.name();
  }

  private static void restoreProfile(Profile previous) {
    if (previous == null) {
      ProfileContext.clear();
    } else {
      ProfileContext.set(previous);
    }
  }

  private static void restoreToolExecution(
      String agent, String backend, Supplier<String> containerId) {
    if (agent == null) {
      ToolExecutionContext.clear();
      return;
    }
    ToolExecutionContext.setAgentName(agent);
    if (backend != null) {
      ToolExecutionContext.setExecution(backend, containerId);
    }
  }
}
