package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** tool_invocations 的写入通道；按 session 查询供测试，按工具名 + 时间窗查询供知识库看板聚合（FR-023）。 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {

  List<ToolInvocation> findBySessionId(String sessionId);

  List<ToolInvocation> findByToolNameAndCreatedAtBetweenOrderByIdDesc(
      String toolName, Instant from, Instant to);

  /** 021：单轮全链路回放——按 trace 取本轮全部工具调用（走 idx_tool_invocations_trace）。 */
  List<ToolInvocation> findByTraceId(String traceId);

  /** 016 看板汇总：窗口内计数/成功率原料/平均耗时一行算完，整窗实体（含 input_json/result_json）不进堆。 */
  @Query(
      """
      select new io.oryxos.storage.ToolAuditTotals(
          count(t),
          sum(case when t.success = true then 1 else 0 end),
          avg(t.durationMs))
        from ToolInvocation t
       where t.createdAt between :from and :to
      """)
  ToolAuditTotals auditTotals(@Param("from") Instant from, @Param("to") Instant to);

  /** 016 看板分布：按工具名分组；key 为 null 即未命名，展示文案由服务层给。 */
  @Query(
      """
      select new io.oryxos.storage.ToolAuditGroup(
          t.toolName,
          count(t),
          sum(case when t.success = true then 1 else 0 end))
        from ToolInvocation t
       where t.createdAt between :from and :to
       group by t.toolName
       order by count(t) desc
      """)
  List<ToolAuditGroup> auditGroupsByName(@Param("from") Instant from, @Param("to") Instant to);

  /**
   * 016 明细下钻：窗口内最近 limit 条，两个可选过滤在同一句里；排序与截断都在 SQL（{@link Pageable}）。
   *
   * <p>blockedBy / executionBackend 为 null 表示不筛，对应 020（FR-006）与 024（SC-007）的筛选口径。
   */
  @Query(
      """
      select t from ToolInvocation t
       where t.createdAt between :from and :to
         and (:blockedBy is null or t.blockedBy = :blockedBy)
         and (:executionBackend is null or t.executionBackend = :executionBackend)
       order by t.createdAt desc, t.id desc
      """)
  List<ToolInvocation> findRecentInWindow(
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("blockedBy") String blockedBy,
      @Param("executionBackend") String executionBackend,
      Pageable pageable);
}
