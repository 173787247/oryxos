package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** instances：副本心跳与运维可见性（026）——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "instances")
public class InstanceHeartbeat {

  @Id
  @Column(name = "instance_id")
  private String instanceId;

  @Column(nullable = false)
  private long epoch;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "last_heartbeat_at", nullable = false)
  private Instant lastHeartbeatAt;

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public long getEpoch() {
    return epoch;
  }

  public void setEpoch(long epoch) {
    this.epoch = epoch;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getLastHeartbeatAt() {
    return lastHeartbeatAt;
  }

  public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
    this.lastHeartbeatAt = lastHeartbeatAt;
  }
}
