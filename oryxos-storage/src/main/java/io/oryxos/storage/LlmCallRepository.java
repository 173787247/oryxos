package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** llm_calls 的写入通道；核心阶段只写不查，按 session 查询仅供测试与后续扩展。 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

  List<LlmCall> findBySessionId(String sessionId);

  /** 021：单轮全链路回放——按 trace 取本轮全部 LLM 调用（走 idx_llm_calls_trace）。 */
  List<LlmCall> findByTraceId(String traceId);

  /** 成本对账（#476）：按 trace 在库里求和，不为一条 trace 把整张 llm_calls 读进内存。 */
  @Query("select coalesce(sum(c.costMicros), 0) from LlmCall c where c.traceId = :traceId")
  long sumCostMicrosByTraceId(@Param("traceId") String traceId);

  /**
   * 016 看板汇总：窗口内的计数/求和/平均在一行里算完。整窗实体不进堆——审计表 append-only，窗口可以大到几十万行。
   *
   * <p>{@code measuredCostCount} 用于区分「未计量 → 成本 null」与「已计量但合计 0 元」。
   */
  @Query(
      """
      select new io.oryxos.storage.LlmAuditTotals(
          count(c),
          sum(case when c.success = true then 1 else 0 end),
          sum(c.promptTokens),
          sum(c.completionTokens),
          sum(c.costMicros),
          count(c.costMicros),
          avg(c.durationMs))
        from LlmCall c
       where c.createdAt between :from and :to
      """)
  LlmAuditTotals auditTotals(@Param("from") Instant from, @Param("to") Instant to);

  /** 016 看板分布：按模型分组（JPQL 不能参数化列名，因此三个维度各一条查询）。 */
  @Query(
      """
      select new io.oryxos.storage.LlmAuditGroup(
          c.model,
          count(c),
          sum(case when c.success = true then 1 else 0 end),
          sum(c.costMicros),
          count(c.costMicros))
        from LlmCall c
       where c.createdAt between :from and :to
       group by c.model
       order by count(c) desc
      """)
  List<LlmAuditGroup> auditGroupsByModel(@Param("from") Instant from, @Param("to") Instant to);

  /** 016 看板分布：按 provider 分组。 */
  @Query(
      """
      select new io.oryxos.storage.LlmAuditGroup(
          c.provider,
          count(c),
          sum(case when c.success = true then 1 else 0 end),
          sum(c.costMicros),
          count(c.costMicros))
        from LlmCall c
       where c.createdAt between :from and :to
       group by c.provider
       order by count(c) desc
      """)
  List<LlmAuditGroup> auditGroupsByProvider(@Param("from") Instant from, @Param("to") Instant to);

  /** 016 看板分布：按 Agent（profile_name）分组；key 为 null 即未归属，展示文案由服务层给。 */
  @Query(
      """
      select new io.oryxos.storage.LlmAuditGroup(
          c.profileName,
          count(c),
          sum(case when c.success = true then 1 else 0 end),
          sum(c.costMicros),
          count(c.costMicros))
        from LlmCall c
       where c.createdAt between :from and :to
       group by c.profileName
       order by count(c) desc
      """)
  List<LlmAuditGroup> auditGroupsByAgent(@Param("from") Instant from, @Param("to") Instant to);

  /** 016 明细下钻：窗口内最近 limit 条；排序与截断都在 SQL（{@link Pageable}），不把整窗读进 Java。 */
  @Query(
      """
      select c from LlmCall c
       where c.createdAt between :from and :to
       order by c.createdAt desc, c.id desc
      """)
  List<LlmCall> findRecentInWindow(
      @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
