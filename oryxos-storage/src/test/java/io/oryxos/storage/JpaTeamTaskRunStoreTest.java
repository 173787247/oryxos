package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.task.TeamTaskResult;
import io.oryxos.core.task.TeamTaskResult.WorkerResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SqliteJpaTest
class JpaTeamTaskRunStoreTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("team-task-runs.db"));
  }

  @Autowired TeamTaskRunRepository repository;

  @Test
  void saveAndFindRoundTripsWorkers() {
    JpaTeamTaskRunStore store = new JpaTeamTaskRunStore(repository);
    TeamTaskResult saved =
        store.save(
            TeamTaskResult.builder()
                .id("task-1")
                .goal("ship it")
                .coordinator("boss")
                .planRaw("{\"subtasks\":[]}")
                .addWorker(new WorkerResult("coder", "write", "done", null))
                .addWorker(new WorkerResult("reviewer", "check", "", "timeout"))
                .summary("ok")
                .build());

    assertEquals("task-1", saved.id());
    TeamTaskResult loaded = store.find("task-1").orElseThrow();
    assertEquals("ship it", loaded.goal());
    assertEquals("boss", loaded.coordinator());
    assertEquals("{\"subtasks\":[]}", loaded.planRaw());
    assertEquals("ok", loaded.summary());
    assertEquals(2, loaded.workers().size());
    assertEquals("coder", loaded.workers().get(0).agent());
    assertEquals("done", loaded.workers().get(0).reply());
    assertEquals("timeout", loaded.workers().get(1).error());
    assertTrue(store.find("missing").isEmpty());
  }
}
