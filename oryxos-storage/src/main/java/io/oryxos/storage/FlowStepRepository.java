package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** flow_steps 仓库（046 / #468）。 */
public interface FlowStepRepository extends JpaRepository<FlowStepEntity, String> {

  Optional<FlowStepEntity> findByIdempotencyKey(String idempotencyKey);

  List<FlowStepEntity> findByRunIdOrderByCreatedAtAscNodeIdAsc(String runId);
}
