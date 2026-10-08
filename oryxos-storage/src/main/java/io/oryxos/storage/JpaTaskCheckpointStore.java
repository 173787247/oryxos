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

  /** 条件更新一条语句写完：rowcount 1 = 迁移成功（写的就是 {@code next} 的那些列），0 = 行不存在或状态不符。 */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<TaskCheckpoint> tryTransition(
      String id, DurableTaskState expected, TaskCheckpoint next) {
    int updated =
        repository.transitionIfState(
            id,
            expected.name(),
            next.executionId(),
            next.sessionId(),
            next.agentName(),
            next.state().name(),
            next.idempotencyKey(),
            next.checkpointKind(),
            next.toolName(),
            next.toolCallId(),
            next.argumentsJson(),
            next.policyVersion(),
            next.ruleId(),
            next.attempt(),
            next.lastError(),
            next.ttlSeconds(),
            next.expiresAt(),
            next.createdAt(),
            next.updatedAt());
    return updated == 1 ? Optional.of(next) : Optional.empty();
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
