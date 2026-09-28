package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.task.TeamTaskResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamTaskAwareFlowNodeHandlerTest {

  private static FlowRun dummyRun() {
    Instant t = Instant.parse("2026-09-28T00:00:00Z");
    return new FlowRun(
        "r1", "flow", "1", "", FlowRunState.RUNNING, "n1", "n1", "{}", "{}", null, 0, t, t);
  }

  @Test
  @DisplayName("parse team_task aliases")
  void parseType() {
    assertEquals(FlowNodeType.TEAM_TASK, FlowNodeType.parse("team_task"));
    assertEquals(FlowNodeType.TEAM_TASK, FlowNodeType.parse("team-task"));
  }

  @Test
  @DisplayName("TEAM_TASK node runs orchestrator and maps summary")
  void executesTeamTask() {
    TeamTaskAwareFlowNodeHandler handler =
        new TeamTaskAwareFlowNodeHandler(
            new DefaultFlowNodeHandler(),
            (goal, coordinator) ->
                TeamTaskResult.builder()
                    .id("tt-1")
                    .goal(goal)
                    .coordinator(coordinator == null ? "c" : coordinator)
                    .planRaw("{}")
                    .summary("DONE:" + goal)
                    .build());

    Map<String, FlowPort> outs = new LinkedHashMap<>();
    outs.put("summary", new FlowPort("summary", FlowPortType.STRING, null, true));
    outs.put("teamTaskId", new FlowPort("teamTaskId", FlowPortType.STRING, null, true));
    FlowNode node = new FlowNode("n1", FlowNodeType.TEAM_TASK, "boss", Map.of(), outs, List.of());

    FlowNodeOutcome out = handler.execute(node, Map.of("goal", "Ship it"), dummyRun());
    assertTrue(out.succeeded());
    assertEquals("DONE:Ship it", out.outputs().get("summary"));
    assertEquals("tt-1", out.outputs().get("teamTaskId"));
  }

  @Test
  @DisplayName("missing goal fails")
  void missingGoal() {
    TeamTaskAwareFlowNodeHandler handler =
        new TeamTaskAwareFlowNodeHandler(new DefaultFlowNodeHandler(), (g, c) -> null);
    FlowNode node = new FlowNode("n1", FlowNodeType.TEAM_TASK, null, Map.of(), Map.of(), List.of());
    FlowNodeOutcome out = handler.execute(node, Map.of(), dummyRun());
    assertTrue(out.failed());
    assertTrue(out.error().contains("goal"));
  }

  @Test
  @DisplayName("non TEAM_TASK falls through")
  void fallthrough() {
    TeamTaskAwareFlowNodeHandler handler =
        new TeamTaskAwareFlowNodeHandler(
            new DefaultFlowNodeHandler(),
            (g, c) -> {
              throw new IllegalStateException("should not run");
            });
    FlowNode node = new FlowNode("n1", FlowNodeType.NOTIFY, null, Map.of(), Map.of(), List.of());
    FlowNodeOutcome out = handler.execute(node, Map.of("topic", "x"), dummyRun());
    assertTrue(out.succeeded());
  }
}
