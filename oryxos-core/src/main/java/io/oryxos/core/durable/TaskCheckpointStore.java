package io.oryxos.core.durable;

import java.util.List;
import java.util.Optional;

/** 检查点持久化契约（043 / #465）：core 只认接口，JPA 在 oryxos-storage。 */
public interface TaskCheckpointStore {

  TaskCheckpoint save(TaskCheckpoint checkpoint);

  Optional<TaskCheckpoint> findById(String id);

  Optional<TaskCheckpoint> findByIdempotencyKey(String idempotencyKey);

  List<TaskCheckpoint> listByState(DurableTaskState state);

  /** 原子态迁移：仅当当前态等于 {@code expected} 时写入 {@code next}。成功返回更新后的快照；冲突返回 empty。 */
  Optional<TaskCheckpoint> tryTransition(String id, DurableTaskState expected, TaskCheckpoint next);
}
