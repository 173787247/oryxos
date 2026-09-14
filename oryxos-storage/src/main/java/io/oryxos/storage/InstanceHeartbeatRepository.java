package io.oryxos.storage;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** 实例心跳（026）：upsert 走 save（主键 merge）；死行批删。 */
public interface InstanceHeartbeatRepository extends JpaRepository<InstanceHeartbeat, String> {

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM InstanceHeartbeat i WHERE i.lastHeartbeatAt < :before")
  int deleteOlderThan(Instant before);
}
