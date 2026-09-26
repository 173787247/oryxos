package io.oryxos.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.task.TeamTaskResult;
import io.oryxos.core.task.TeamTaskResult.WorkerResult;
import io.oryxos.core.task.TeamTaskRunStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** {@link TeamTaskRunStore} 的 JPA 实现（Direction I）。 */
public class JpaTeamTaskRunStore implements TeamTaskRunStore {

  private final TeamTaskRunRepository repository;
  private final ObjectMapper mapper = new ObjectMapper();

  public JpaTeamTaskRunStore(TeamTaskRunRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public TeamTaskResult save(TeamTaskResult result) {
    Objects.requireNonNull(result, "result");
    if (result.id() == null || result.id().isBlank()) {
      throw new IllegalArgumentException("result.id must not be blank");
    }
    TeamTaskRunEntity e = new TeamTaskRunEntity();
    e.setId(result.id());
    e.setGoal(result.goal());
    e.setCoordinator(result.coordinator());
    e.setPlanRaw(result.planRaw());
    e.setWorkersJson(writeWorkers(result.workers()));
    e.setSummary(result.summary());
    repository.save(e);
    return result;
  }

  @Override
  public Optional<TeamTaskResult> find(String taskId) {
    if (taskId == null || taskId.isBlank()) {
      return Optional.empty();
    }
    return repository.findById(taskId.strip()).map(this::toDomain);
  }

  private TeamTaskResult toDomain(TeamTaskRunEntity e) {
    return new TeamTaskResult(
        e.getId(),
        e.getGoal(),
        e.getCoordinator(),
        e.getPlanRaw(),
        readWorkers(e.getWorkersJson()),
        e.getSummary());
  }

  private String writeWorkers(List<WorkerResult> workers) {
    try {
      List<Map<String, String>> rows = new ArrayList<>();
      for (WorkerResult w : workers) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("agent", w.agent());
        row.put("message", w.message());
        row.put("reply", w.reply());
        if (w.error() != null) {
          row.put("error", w.error());
        }
        rows.add(row);
      }
      return mapper.writeValueAsString(rows);
    } catch (Exception ex) {
      throw new IllegalStateException("serialize team-task workers", ex);
    }
  }

  private List<WorkerResult> readWorkers(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, String>> rows =
          mapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
      List<WorkerResult> out = new ArrayList<>(rows.size());
      for (Map<String, String> row : rows) {
        out.add(
            new WorkerResult(
                row.get("agent"), row.get("message"), row.get("reply"), row.get("error")));
      }
      return out;
    } catch (Exception ex) {
      throw new IllegalStateException("deserialize team-task workers", ex);
    }
  }
}
