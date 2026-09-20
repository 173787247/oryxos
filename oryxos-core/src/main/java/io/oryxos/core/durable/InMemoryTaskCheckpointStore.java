package io.oryxos.core.durable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内检查点存储（单测 / 未装配 JPA 时）。 */
public final class InMemoryTaskCheckpointStore implements TaskCheckpointStore {

  private final Map<String, TaskCheckpoint> byId = new ConcurrentHashMap<>();
  private final Map<String, String> idempotencyToId = new ConcurrentHashMap<>();

  @Override
  public TaskCheckpoint save(TaskCheckpoint checkpoint) {
    byId.put(checkpoint.id(), checkpoint);
    idempotencyToId.put(checkpoint.idempotencyKey(), checkpoint.id());
    return checkpoint;
  }

  @Override
  public Optional<TaskCheckpoint> findById(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override
  public Optional<TaskCheckpoint> findByIdempotencyKey(String idempotencyKey) {
    String id = idempotencyToId.get(idempotencyKey);
    return id == null ? Optional.empty() : findById(id);
  }

  @Override
  public List<TaskCheckpoint> listByState(DurableTaskState state) {
    List<TaskCheckpoint> out = new ArrayList<>();
    for (TaskCheckpoint cp : byId.values()) {
      if (cp.state() == state) {
        out.add(cp);
      }
    }
    return List.copyOf(out);
  }

  @Override
  public Optional<TaskCheckpoint> tryTransition(
      String id, DurableTaskState expected, TaskCheckpoint next) {
    synchronized (this) {
      TaskCheckpoint cur = byId.get(id);
      if (cur == null || cur.state() != expected) {
        return Optional.empty();
      }
      byId.put(id, next);
      idempotencyToId.put(next.idempotencyKey(), id);
      return Optional.of(next);
    }
  }
}
