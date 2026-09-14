package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** turn 租约的 CAS 面（026）：条件更新 rowcount 语义，仿 SessionRepository.updateMessagesIfUnchanged。 */
public interface TurnLeaseRepository extends JpaRepository<TurnLeaseEntity, String> {

  /** 认领插入：native INSERT 保证真插入语义（JPA save 对自然主键实体走 merge，会静默覆写他人持有—— 契约测试抓到的坑）；主键冲突由调用方按约束违规捕获判定。 */
  @Transactional(rollbackFor = Exception.class)
  @Modifying
  @Query(
      value =
          "INSERT INTO session_turn_leases (session_id, owner, lease_until, acquired_at,"
              + " agent_execution_id) VALUES (:sessionId, :owner, :leaseUntil, :acquiredAt, NULL)",
      nativeQuery = true)
  void insertLease(String sessionId, String owner, Instant leaseUntil, Instant acquiredAt);

  /** 抢过期：只有 lease_until 已过（持有者死）才改写 owner——绝无无条件覆写路径。 */
  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE TurnLeaseEntity l SET l.owner = :owner, l.leaseUntil = :leaseUntil,"
          + " l.acquiredAt = :now, l.agentExecutionId = NULL"
          + " WHERE l.sessionId = :sessionId AND l.leaseUntil < :now")
  int takeExpired(String sessionId, String owner, Instant leaseUntil, Instant now);

  /** 续租 fencing：owner 不符 rowcount=0 = 已被回收，执行方必须中止。 */
  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE TurnLeaseEntity l SET l.leaseUntil = :leaseUntil"
          + " WHERE l.sessionId = :sessionId AND l.owner = :owner")
  int renew(String sessionId, String owner, Instant leaseUntil);

  /** 释放：只删自己的。 */
  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM TurnLeaseEntity l WHERE l.sessionId = :sessionId AND l.owner = :owner")
  int release(String sessionId, String owner);

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE TurnLeaseEntity l SET l.agentExecutionId = :executionId"
          + " WHERE l.sessionId = :sessionId AND l.owner = :owner")
  int attachExecution(String sessionId, String owner, Long executionId);

  Optional<TurnLeaseEntity> findBySessionId(String sessionId);

  List<TurnLeaseEntity> findByLeaseUntilAfter(Instant now);

  /** 时间基准：数据库时间而非副本本地时钟（两库通用标准 SQL）；返回类型随驱动而异，调用方适配。 */
  @Query(value = "SELECT CURRENT_TIMESTAMP", nativeQuery = true)
  Object dbNowRaw();
}
