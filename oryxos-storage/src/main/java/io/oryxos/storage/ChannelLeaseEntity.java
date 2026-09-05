package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** channel_leases：独连型渠道连接属主（026，企微）——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "channel_leases")
public class ChannelLeaseEntity {

  @Id
  @Column(name = "channel_name")
  private String channelName;

  @Column(nullable = false)
  private String owner;

  @Column(name = "lease_until", nullable = false)
  private Instant leaseUntil;

  public String getChannelName() {
    return channelName;
  }

  public void setChannelName(String channelName) {
    this.channelName = channelName;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public void setLeaseUntil(Instant leaseUntil) {
    this.leaseUntil = leaseUntil;
  }
}
