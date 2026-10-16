package io.oryxos.storage;

import java.time.Instant;
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

  /**
   * 与 {@link #search} 同一套筛选条件（含时间范围），在库里求和（#476）：账本是 append-only 且随调用增长，预算检查每次调用都要
   * 读它，不能把命中的明细行整表水合进堆。
   *
   * <p>{@code from}/{@code to} <b>必须非空</b>：时间范围的开区间由调用方传哨兵值（见 {@code JpaCostLedgerStore}）。 「{@code
   * :from is null or …}」那种写法在 Postgres 上会以 {@code could not determine data type of parameter} 失败 ——
   * null 的时间参数没有类型可推断；SQLite 不报错，所以只有两库同套的契约用例拦得住。
   */
  @Query(
      """
      select coalesce(sum(e.llmCostMicros), 0) from CostLedgerEntryEntity e
      where (:runId is null or e.runId = :runId)
        and (:taskId is null or e.taskId = :taskId)
        and (:agentName is null or e.agentName = :agentName)
        and (:teamId is null or e.teamId = :teamId)
        and (:provider is null or e.provider = :provider)
        and (:model is null or e.model = :model)
        and e.createdAt >= :from
        and e.createdAt < :to
      """)
  long sumLlmCostMicros(
      @Param("runId") String runId,
      @Param("taskId") String taskId,
      @Param("agentName") String agentName,
      @Param("teamId") String teamId,
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /** 同上，工具侧成本（{@code from}/{@code to} 同样必须非空）。 */
  @Query(
      """
      select coalesce(sum(e.toolCostMicros), 0) from CostLedgerEntryEntity e
      where (:runId is null or e.runId = :runId)
        and (:taskId is null or e.taskId = :taskId)
        and (:agentName is null or e.agentName = :agentName)
        and (:teamId is null or e.teamId = :teamId)
        and (:provider is null or e.provider = :provider)
        and (:model is null or e.model = :model)
        and e.createdAt >= :from
        and e.createdAt < :to
      """)
  long sumToolCostMicros(
      @Param("runId") String runId,
      @Param("taskId") String taskId,
      @Param("agentName") String agentName,
      @Param("teamId") String teamId,
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /** 去重候选值（053 筛选器下拉）：非空、按值排序。 */
  @Query(
      "select distinct e.agentName from CostLedgerEntryEntity e where e.agentName is not null order by e.agentName")
  List<String> distinctAgents();

  @Query(
      "select distinct e.provider from CostLedgerEntryEntity e where e.provider is not null order by e.provider")
  List<String> distinctProviders();

  @Query(
      "select distinct e.model from CostLedgerEntryEntity e where e.model is not null order by e.model")
  List<String> distinctModels();

  @Query(
      "select distinct e.teamId from CostLedgerEntryEntity e where e.teamId is not null order by e.teamId")
  List<String> distinctTeams();
}
