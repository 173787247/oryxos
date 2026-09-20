package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CostLedgerEntryRepository extends JpaRepository<CostLedgerEntryEntity, Long> {

  @Query(
      """
      select e from CostLedgerEntryEntity e
      where (:runId is null or e.runId = :runId)
        and (:taskId is null or e.taskId = :taskId)
        and (:agentName is null or e.agentName = :agentName)
        and (:teamId is null or e.teamId = :teamId)
        and (:provider is null or e.provider = :provider)
        and (:model is null or e.model = :model)
      order by e.id asc
      """)
  List<CostLedgerEntryEntity> search(
      @Param("runId") String runId,
      @Param("taskId") String taskId,
      @Param("agentName") String agentName,
      @Param("teamId") String teamId,
      @Param("provider") String provider,
      @Param("model") String model);
}
