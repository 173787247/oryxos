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

  /** 与 {@link #search} 同一套筛选条件，在库里求和（#476）：账本是 append-only 且随调用增长，预算检查每次调用都要 读它，不能把命中的明细行整表水合进堆。 */
  @Query(
      """
      select coalesce(sum(e.llmCostMicros), 0) from CostLedgerEntryEntity e
      where (:runId is null or e.runId = :runId)
        and (:taskId is null or e.taskId = :taskId)
        and (:agentName is null or e.agentName = :agentName)
        and (:teamId is null or e.teamId = :teamId)
        and (:provider is null or e.provider = :provider)
        and (:model is null or e.model = :model)
      """)
  long sumLlmCostMicros(
      @Param("runId") String runId,
      @Param("taskId") String taskId,
      @Param("agentName") String agentName,
      @Param("teamId") String teamId,
      @Param("provider") String provider,
      @Param("model") String model);

  /** 同上，工具侧成本。 */
  @Query(
      """
      select coalesce(sum(e.toolCostMicros), 0) from CostLedgerEntryEntity e
      where (:runId is null or e.runId = :runId)
        and (:taskId is null or e.taskId = :taskId)
        and (:agentName is null or e.agentName = :agentName)
        and (:teamId is null or e.teamId = :teamId)
        and (:provider is null or e.provider = :provider)
        and (:model is null or e.model = :model)
      """)
  long sumToolCostMicros(
      @Param("runId") String runId,
      @Param("taskId") String taskId,
      @Param("agentName") String agentName,
      @Param("teamId") String teamId,
      @Param("provider") String provider,
      @Param("model") String model);
}
