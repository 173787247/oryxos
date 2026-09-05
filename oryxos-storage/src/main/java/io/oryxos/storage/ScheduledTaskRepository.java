package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** scheduled_tasks 读写通道。 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {

  Optional<ScheduledTask> findByProfileNameAndScheduleKey(String profileName, String scheduleKey);

  List<ScheduledTask> findByRetiredFalse();

  List<ScheduledTask> findByScheduleKeyAndRetiredFalse(String scheduleKey);

  /**
   * 026 到点认领 CAS：fireTime 为 CronTrigger 理论触发时刻（各副本同值）；rowcount==1 即本副本执行。
   * 已被认领过更晚（或同一）到点时条件不满足——恰好一次。
   */
  @org.springframework.transaction.annotation.Transactional
  @org.springframework.data.jpa.repository.Modifying(
      clearAutomatically = true,
      flushAutomatically = true)
  @org.springframework.data.jpa.repository.Query(
      "UPDATE ScheduledTask t SET t.claimedFireTime = :fireTime, t.claimedBy = :owner"
          + " WHERE t.scheduleId = :scheduleId"
          + " AND (t.claimedFireTime IS NULL OR t.claimedFireTime < :fireTime)")
  int claimFireTime(String scheduleId, java.time.Instant fireTime, String owner);
}
