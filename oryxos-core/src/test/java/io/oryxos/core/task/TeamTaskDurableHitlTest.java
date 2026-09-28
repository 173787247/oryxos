package io.oryxos.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.ToolResult;
import io.oryxos.core.durable.ApprovalSuspendedException;
import io.oryxos.core.durable.DurableTaskReplay;
import io.oryxos.core.durable.DurableTaskService;
import io.oryxos.core.durable.DurableTaskState;
import io.oryxos.core.durable.InMemoryTaskCheckpointStore;
import io.oryxos.core.policy.ApprovalOutcome;
import io.oryxos.core.policy.ApprovalPolicyDecision;
import io.oryxos.core.policy.ApprovalPolicyService;
import io.oryxos.core.policy.HighRiskActionType;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamTaskDurableHitlTest {

  @Test
  @DisplayName("REQUIRE_APPROVAL + durable suspends before fan-out")
  void durableSuspend_beforeRun() {
    AtomicInteger calls = new AtomicInteger();
    TeamAgentRunner runner =
        (agent, msg) -> {
          calls.incrementAndGet();
          return "should-not-run";
        };
    DurableTaskService durable =
        new DurableTaskService(
            new InMemoryTaskCheckpointStore(),
            Clock.systemUTC(),
            ApprovalPolicyService.PASS_THROUGH,
            true);
    TeamTaskOrchestrator orch = new TeamTaskOrchestrator(runner, "coordinator", 4);
    orch.setApprovalPolicy(requireTeamTask());
    orch.setDurableTasks(durable);

    ApprovalSuspendedException ex =
        assertThrows(ApprovalSuspendedException.class, () -> orch.run("Ship"));
    assertTrue(ex.checkpointId() != null && !ex.checkpointId().isBlank());
    assertEquals(0, calls.get());
    assertEquals(
        DurableTaskState.WAITING_APPROVAL,
        durable.findById(ex.checkpointId()).orElseThrow().state());
  }

  @Test
  @DisplayName("approve resumes team-task via DurableTaskReplay")
  void approve_resumesTeamTask() {
    AtomicInteger calls = new AtomicInteger();
    TeamAgentRunner runner =
        (agent, msg) -> {
          calls.incrementAndGet();
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"draft\"}]}";
          }
          if (msg.startsWith("Summarize")) {
            return "DONE";
          }
          return "ok";
        };
    DurableTaskService durable =
        new DurableTaskService(
            new InMemoryTaskCheckpointStore(),
            Clock.systemUTC(),
            ApprovalPolicyService.PASS_THROUGH,
            true);
    TeamTaskOrchestrator orch = new TeamTaskOrchestrator(runner, "coordinator", 4);
    orch.setApprovalPolicy(requireTeamTask());
    orch.setDurableTasks(durable);

    ApprovalSuspendedException suspended =
        assertThrows(ApprovalSuspendedException.class, () -> orch.run("Ship"));

    // After suspend, wire resumer like Runtime does; grant path skips gate.
    DurableTaskReplay replay =
        new DurableTaskReplay(
            durable,
            new io.oryxos.core.agent.ToolExecutor(
                java.util.Map.of(), mock(io.oryxos.core.agent.ToolInvocationAuditor.class)));
    replay.setTeamTaskResumer(
        (agentName, argsJson) -> {
          String goal = TeamTaskApprovalGate.goalFromArgs(argsJson);
          String coord = TeamTaskApprovalGate.coordinatorFromArgs(argsJson);
          TeamTaskResult result = orch.run(goal, coord);
          return ToolResult.ok(result.summary());
        });

    DurableTaskReplay.ReplayOutcome out =
        replay.resume(suspended.checkpointId(), true, "ops", "lgtm");
    assertEquals(DurableTaskState.SUCCEEDED, out.checkpoint().state());
    assertTrue(out.toolResult() != null && out.toolResult().success());
    assertEquals("DONE", out.toolResult().content());
    assertTrue(calls.get() >= 3); // plan + worker + summary
  }

  private static ApprovalPolicyService requireTeamTask() {
    return (agent, tool, args) ->
        new ApprovalPolicyDecision(
            ApprovalOutcome.REQUIRE_APPROVAL,
            "1",
            "team-rule",
            HighRiskActionType.TEAM_TASK,
            List.of("ops"),
            120,
            "confirm team run");
  }
}
