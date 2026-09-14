package io.oryxos.storage;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** 知识索引构建认领 CAS（027）：语义同 ChannelLeaseRepository（互斥 + 抢过期 + fencing 续租 + owner 条件释放）。 */
public interface KnowledgeBuildClaimRepository
    extends JpaRepository<KnowledgeBuildClaimEntity, String> {

  @Transactional(rollbackFor = Exception.class)
  @Modifying
  @Query(
      value =
          "INSERT INTO knowledge_build_claims (kb_name, owner, lease_until, generation)"
              + " VALUES (:kbName, :owner, :leaseUntil, :generation)",
      nativeQuery = true)
  void insertClaim(String kbName, String owner, Instant leaseUntil, long generation);

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE KnowledgeBuildClaimEntity c SET c.owner = :owner, c.leaseUntil = :leaseUntil,"
          + " c.generation = :generation WHERE c.kbName = :kbName AND c.leaseUntil < :now")
  int takeExpired(String kbName, String owner, Instant leaseUntil, long generation, Instant now);

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE KnowledgeBuildClaimEntity c SET c.leaseUntil = :leaseUntil"
          + " WHERE c.kbName = :kbName AND c.owner = :owner")
  int renew(String kbName, String owner, Instant leaseUntil);

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM KnowledgeBuildClaimEntity c WHERE c.kbName = :kbName AND c.owner = :owner")
  int release(String kbName, String owner);
}
