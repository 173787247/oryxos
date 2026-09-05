package io.oryxos.storage;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** 渠道属主租约 CAS（026）：语义同 TurnLeaseRepository。 */
public interface ChannelLeaseRepository extends JpaRepository<ChannelLeaseEntity, String> {

  @Transactional
  @Modifying
  @Query(
      value =
          "INSERT INTO channel_leases (channel_name, owner, lease_until)"
              + " VALUES (:channelName, :owner, :leaseUntil)",
      nativeQuery = true)
  void insertLease(String channelName, String owner, Instant leaseUntil);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE ChannelLeaseEntity l SET l.owner = :owner, l.leaseUntil = :leaseUntil"
          + " WHERE l.channelName = :channelName AND l.leaseUntil < :now")
  int takeExpired(String channelName, String owner, Instant leaseUntil, Instant now);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE ChannelLeaseEntity l SET l.leaseUntil = :leaseUntil"
          + " WHERE l.channelName = :channelName AND l.owner = :owner")
  int renew(String channelName, String owner, Instant leaseUntil);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM ChannelLeaseEntity l WHERE l.channelName = :channelName AND l.owner = :owner")
  int release(String channelName, String owner);
}
