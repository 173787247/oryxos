package io.oryxos.storage;

import io.oryxos.core.durable.DurableTaskState;
import io.oryxos.core.durable.TaskCheckpoint;
import io.oryxos.core.durable.TaskCheckpointStore;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** {@link TaskCheckpointStore} 的 JPA 实现（043 / #465）。 */
public class JpaTaskCheckpointStore implements TaskCheckpointStore {

  private final DurableTaskCheckpointRepository repository;

  public JpaTaskCheckpointStore(DurableTaskCheckpointRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public TaskCheckpoint save(TaskCheckpoint checkpoint) {
    repository.save(toEntity(checkpoint));
    return checkpoint;
  }

  @Override
  public Optional<TaskCheckpoint> findById(String id) {
    return repository.findById(id).map(JpaTaskCheckpointStore::toView);
  }

  @Override
  public Optional<TaskCheckpoint> findByIdempotencyKey(String idempotencyKey) {
    return repository.findByIdempotencyKey(idempotencyKey).map(JpaTaskCheckpointStore::toView);
  }

  @Override
  public List<TaskCheckpoint> listByState(DurableTaskState state) {
    return repository.findByState(state.name()).stream()
        .map(JpaTaskCheckpointStore::toView)
        .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<TaskCheckpoint> tryTransition(
      String id, DurableTaskState expected, TaskCheckpoint next) {
    DurableTaskCheckpointEntity e = repository.findById(id).orElse(null);
    if (e == null || !expected.name().equals(e.getState())) {
      return Optional.empty();
    }
    repository.save(toEntity(next));
    return Optional.of(next);
  }

  private static TaskCheckpoint toView(DurableTaskCheckpointEntity e) {
    return new TaskCheckpoint(
        e.getId(),
        e.getExecutionId(),
        e.getSessionId(),
        e.getAgentName(),
        DurableTaskState.valueOf(e.getState()),
        e.getIdempotencyKey(),
        e.getCheckpointKind(),
        e.getToolName(),
        e.getToolCallId(),
        e.getArgumentsJson(),
        e.getPolicyVersion(),
        e.getRuleId(),
        e.getAttempt(),
        e.getLastError(),
        e.getTtlSeconds(),
        e.getExpiresAt(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }

  private static DurableTaskCheckpointEntity toEntity(TaskCheckpoint cp) {
    DurableTaskCheckpointEntity e = new DurableTaskCheckpointEntity();
    e.setId(cp.id());
    e.setExecutionId(cp.executionId());
    e.setSessionId(cp.sessionId());
    e.setAgentName(cp.agentName());
    e.setState(cp.state().name());
    e.setIdempotencyKey(cp.idempotencyKey());
    e.setCheckpointKind(cp.checkpointKind());
    e.setToolName(cp.toolName());
    e.setToolCallId(cp.toolCallId());
    e.setArgumentsJson(cp.argumentsJson());
    e.setPolicyVersion(cp.policyVersion());
    e.setRuleId(cp.ruleId());
    e.setAttempt(cp.attempt());
    e.setLastError(cp.lastError());
    e.setTtlSeconds(cp.ttlSeconds());
    e.setExpiresAt(cp.expiresAt());
    e.setCreatedAt(cp.createdAt());
    e.setUpdatedAt(cp.updatedAt());
    return e;
  }
}
