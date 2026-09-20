package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** durable_task_checkpoints 仓库（043 / #465）。 */
public interface DurableTaskCheckpointRepository
    extends JpaRepository<DurableTaskCheckpointEntity, String> {

  Optional<DurableTaskCheckpointEntity> findByIdempotencyKey(String idempotencyKey);

  List<DurableTaskCheckpointEntity> findByState(String state);
}
