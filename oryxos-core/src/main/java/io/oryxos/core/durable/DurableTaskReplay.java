package io.oryxos.core.durable;

import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.task.TeamTaskApprovalGate;
import java.util.Objects;
import java.util.function.BiFunction;

/** 从最近安全检查点恢复并回放待批工具（043 / #465）。管理台/IM（#466）批准后调用本门面；本刀不暴露 HTTP。 */
public final class DurableTaskReplay {

  private final DurableTaskService tasks;
  private final ToolExecutor toolExecutor;

  /** Optional Direction I resume: (agentName, argumentsJson) → ToolResult for {@code team_task}. */
  private BiFunction<String, String, ToolResult> teamTaskResumer;

  public DurableTaskReplay(DurableTaskService tasks, ToolExecutor toolExecutor) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
  }

  public void setTeamTaskResumer(BiFunction<String, String, ToolResult> teamTaskResumer) {
    this.teamTaskResumer = teamTaskResumer;
  }

  /** 批准后回放：态迁移 → 带 grant 执行工具 / team-task → 收敛 SUCCEEDED/FAILED。拒绝则 CANCELLED。幂等：终态直接返回。 */
  public ReplayOutcome resume(String checkpointId, boolean approved, String actor, String comment) {
    TaskCheckpoint before =
        tasks
            .findById(checkpointId)
            .orElseThrow(() -> new IllegalArgumentException("检查点不存在: " + checkpointId));
    if (before.state().terminal()) {
      return new ReplayOutcome(before, null, true);
    }

    TaskCheckpoint decided = tasks.applyDecision(checkpointId, approved, actor, comment);
    if (!approved) {
      return new ReplayOutcome(decided, null, false);
    }
    if (decided.state() != DurableTaskState.RUNNING) {
      return new ReplayOutcome(decided, null, decided.state().terminal());
    }

    ToolResult result;
    try (ApprovalGrantContext.Scope ignored =
        ApprovalGrantContext.open(
            new ApprovalGrantContext.Grant(
                decided.id(), decided.toolName(), decided.toolCallId()))) {
      if (TeamTaskApprovalGate.TOOL_NAME.equals(decided.toolName()) && teamTaskResumer != null) {
        result = teamTaskResumer.apply(decided.agentName(), decided.argumentsJson());
      } else {
        result =
            toolExecutor.execute(
                decided.sessionId(), decided.agentName(), tasks.pendingCall(decided));
      }
    }
    boolean ok = result != null && result.success();
    String err = result == null ? "null result" : result.errorMessage();
    TaskCheckpoint done = tasks.completeReplay(checkpointId, ok, err);
    return new ReplayOutcome(done, result, false);
  }

  public record ReplayOutcome(
      TaskCheckpoint checkpoint, ToolResult toolResult, boolean alreadyDone) {}
}
