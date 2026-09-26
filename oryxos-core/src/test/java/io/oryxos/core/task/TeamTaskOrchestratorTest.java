package io.oryxos.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamTaskOrchestratorTest {

  @Test
  @DisplayName("parsePlan extracts JSON from prose")
  void parsePlan_fromProse() {
    TeamTaskPlan plan =
        TeamTaskOrchestrator.parsePlan(
            "Sure.\n{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"draft README\"}]}\nThanks");
    assertEquals(1, plan.subtasks().size());
    assertEquals("writer", plan.subtasks().get(0).agent());
  }

  @Test
  @DisplayName("run fans out to workers then summarizes")
  void run_fansOut() {
    Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    TeamAgentRunner runner =
        (agent, msg) -> {
          calls.computeIfAbsent(agent, a -> new AtomicInteger()).incrementAndGet();
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"researcher\",\"message\":\"find facts\"},{\"agent\":\"writer\",\"message\":\"write blurb\"}]}";
          }
          if (msg.startsWith("Summarize")) {
            return "DONE";
          }
          return "ok:" + agent + ":" + msg;
        };
    TeamTaskOrchestrator orch = new TeamTaskOrchestrator(runner, "coordinator", 4);
    TeamTaskResult result = orch.run("Ship a brief");
    assertEquals("coordinator", result.coordinator());
    assertEquals(2, result.workers().size());
    assertEquals("DONE", result.summary());
    assertTrue(calls.get("coordinator").get() >= 2);
    assertEquals(1, calls.get("researcher").get());
    assertEquals(1, calls.get("writer").get());
  }

  @Test
  @DisplayName("blank goal fails")
  void blankGoal_fails() {
    TeamTaskOrchestrator orch = new TeamTaskOrchestrator((a, m) -> "", "c", 2);
    assertThrows(IllegalArgumentException.class, () -> orch.run("  "));
  }

  @Test
  @DisplayName("maxSubtasks caps fan-out")
  void maxCaps() {
    List<String> workers = new ArrayList<>();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":["
                + "{\"agent\":\"a1\",\"message\":\"1\"},"
                + "{\"agent\":\"a2\",\"message\":\"2\"},"
                + "{\"agent\":\"a3\",\"message\":\"3\"}"
                + "]}";
          }
          if (!msg.startsWith("Summarize")) {
            workers.add(agent);
          }
          return "ok";
        };
    TeamTaskResult r = new TeamTaskOrchestrator(runner, "c", 2).run("goal");
    assertEquals(2, r.workers().size());
    assertEquals(List.of("a1", "a2"), workers);
  }

  @Test
  @DisplayName("persist assigns id and find returns it")
  void persist_and_find() {
    InMemoryTeamTaskRunStore store = new InMemoryTeamTaskRunStore();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"draft\"}]}";
          }
          return "ok";
        };
    TeamTaskOrchestrator orch =
        new TeamTaskOrchestrator(
            runner, "coordinator", 4, store, () -> java.util.List.of("writer"));
    TeamTaskResult result = orch.run("Ship");
    assertTrue(result.id() != null && !result.id().isBlank());
    assertEquals(result.id(), orch.find(result.id()).orElseThrow().id());
    assertTrue(orch.find("missing").isEmpty());
  }
}
