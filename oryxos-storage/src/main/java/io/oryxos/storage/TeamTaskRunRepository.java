package io.oryxos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** team_task_runs 仓库（Direction I JPA）。 */
public interface TeamTaskRunRepository extends JpaRepository<TeamTaskRunEntity, String> {

  List<TeamTaskRunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
