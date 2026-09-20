package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** flow_runs 仓库（046 / #468）。 */
public interface FlowRunRepository extends JpaRepository<FlowRunEntity, String> {

  List<FlowRunEntity> findByState(String state);
}
