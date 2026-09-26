package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** team_task_runs 仓库（Direction I JPA）。 */
public interface TeamTaskRunRepository extends JpaRepository<TeamTaskRunEntity, String> {}
