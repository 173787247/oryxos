package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** flow_runs 仓库（046 / #468）。 */
public interface FlowRunRepository extends JpaRepository<FlowRunEntity, String> {

  List<FlowRunEntity> findByState(String state);

  /**
   * 原子态迁移：仅当行内 state 等于 {@code expected} 时写全列，rowcount 即结论（1 = 迁移成功，0 = 行不存在或状态不符）。
   *
   * <p>条件是 WHERE 的一部分，与 SET 同一条语句：主键是赋值型 String，{@code save} 走 merge 的无条件 {@code UPDATE ... WHERE
   * id = ?} 没有状态谓词，两个调用方都能先读到 expected 态再各自覆写。
   *
   * <p>SET 与 {@code JpaFlowRunStore#toEntity} 写的列逐一对应（{@code id} 在主键上，不进 SET）。
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional(rollbackFor = Exception.class)
  @Query(
      """
      update FlowRunEntity e
         set e.flowId = :flowId,
             e.flowVersion = :flowVersion,
             e.definitionMarkdown = :definitionMarkdown,
             e.state = :state,
             e.entryNodeId = :entryNodeId,
             e.currentNodeId = :currentNodeId,
             e.inputsJson = :inputsJson,
             e.contextJson = :contextJson,
             e.lastError = :lastError,
             e.attempt = :attempt,
             e.createdAt = :createdAt,
             e.updatedAt = :updatedAt
       where e.id = :runId and e.state = :expected
      """)
  int transitionRunIfState(
      @Param("runId") String runId,
      @Param("expected") String expected,
      @Param("flowId") String flowId,
      @Param("flowVersion") String flowVersion,
      @Param("definitionMarkdown") String definitionMarkdown,
      @Param("state") String state,
      @Param("entryNodeId") String entryNodeId,
      @Param("currentNodeId") String currentNodeId,
      @Param("inputsJson") String inputsJson,
      @Param("contextJson") String contextJson,
      @Param("lastError") String lastError,
      @Param("attempt") int attempt,
      @Param("createdAt") Instant createdAt,
      @Param("updatedAt") Instant updatedAt);
}
