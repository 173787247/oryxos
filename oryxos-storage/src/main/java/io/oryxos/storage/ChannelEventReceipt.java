package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** channel_event_receipts：入站事件跨副本判重回执（026）——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "channel_event_receipts")
public class ChannelEventReceipt {

  @Id
  @Column(name = "receipt_key")
  private String receiptKey;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  public String getReceiptKey() {
    return receiptKey;
  }

  public void setReceiptKey(String receiptKey) {
    this.receiptKey = receiptKey;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public void setFirstSeenAt(Instant firstSeenAt) {
    this.firstSeenAt = firstSeenAt;
  }
}
