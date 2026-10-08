package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** durable_task_checkpoints 仓库（043 / #465）。 */
public interface DurableTaskCheckpointRepository
    extends JpaRepository<DurableTaskCheckpointEntity, String> {

  Optional<DurableTaskCheckpointEntity> findByIdempotencyKey(String idempotencyKey);

  List<DurableTaskCheckpointEntity> findByState(String state);

  /**
   * 原子态迁移：仅当行内 state 等于 {@code expected} 时写全列，rowcount 即结论（1 = 迁移成功，0 = 行不存在或状态不符）。
   *
   * <p>条件是 WHERE 的一部分，与 SET 同一条语句：主键是赋值型 String，{@code save} 走 merge 的无条件 {@code UPDATE ... WHERE
   * id = ?} 没有状态谓词，两个调用方都能先读到 expected 态再各自覆写。
   *
   * <p>SET 与 {@code JpaTaskCheckpointStore#toEntity} 写的列逐一对应（{@code id} 在主键上，不进 SET）。
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional(rollbackFor = Exception.class)
  @Query(
      """
      update DurableTaskCheckpointEntity e
         set e.executionId = :executionId,
             e.sessionId = :sessionId,
             e.agentName = :agentName,
             e.state = :state,
             e.idempotencyKey = :idempotencyKey,
             e.checkpointKind = :checkpointKind,
             e.toolName = :toolName,
             e.toolCallId = :toolCallId,
             e.argumentsJson = :argumentsJson,
             e.policyVersion = :policyVersion,
             e.ruleId = :ruleId,
             e.attempt = :attempt,
             e.lastError = :lastError,
             e.ttlSeconds = :ttlSeconds,
             e.expiresAt = :expiresAt,
             e.createdAt = :createdAt,
             e.updatedAt = :updatedAt
       where e.id = :id and e.state = :expected
      """)
  int transitionIfState(
      @Param("id") String id,
      @Param("expected") String expected,
      @Param("executionId") Long executionId,
      @Param("sessionId") String sessionId,
      @Param("agentName") String agentName,
      @Param("state") String state,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("checkpointKind") String checkpointKind,
      @Param("toolName") String toolName,
      @Param("toolCallId") String toolCallId,
      @Param("argumentsJson") String argumentsJson,
      @Param("policyVersion") String policyVersion,
      @Param("ruleId") String ruleId,
      @Param("attempt") int attempt,
      @Param("lastError") String lastError,
      @Param("ttlSeconds") Integer ttlSeconds,
      @Param("expiresAt") Instant expiresAt,
      @Param("createdAt") Instant createdAt,
      @Param("updatedAt") Instant updatedAt);
}
