package io.oryxos.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryTeamTaskRunStoreTest {

  @Test
  @DisplayName("listRecent is newest-first and respects limit")
  void listRecent_orderAndLimit() {
    InMemoryTeamTaskRunStore store = new InMemoryTeamTaskRunStore();
    store.save(result("a", "g1"));
    store.save(result("b", "g2"));
    store.save(result("c", "g3"));
    List<TeamTaskResult> recent = store.listRecent(2);
    assertEquals(2, recent.size());
    assertEquals("c", recent.get(0).id());
    assertEquals("b", recent.get(1).id());
    assertEquals(3, store.listRecent(100).size());
    assertEquals(20, InMemoryTeamTaskRunStore.normalizeLimit(0));
    assertEquals(100, InMemoryTeamTaskRunStore.normalizeLimit(999));
  }

  @Test
  @DisplayName("orchestrator listRecent delegates to store")
  void orch_listRecent() {
    InMemoryTeamTaskRunStore store = new InMemoryTeamTaskRunStore();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"x\"}]}";
          }
          return "ok";
        };
    TeamTaskOrchestrator orch =
        new TeamTaskOrchestrator(runner, "c", 4, store, () -> List.of("writer"));
    TeamTaskResult r = orch.run("goal");
    assertTrue(orch.listRecent(10).stream().anyMatch(x -> x.id().equals(r.id())));
  }

  private static TeamTaskResult result(String id, String goal) {
    return TeamTaskResult.builder()
        .id(id)
        .goal(goal)
        .coordinator("c")
        .planRaw("{}")
        .summary("s")
        .build();
  }
}
