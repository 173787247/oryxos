package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** approval_callback_receipts：044 / #466 IM 回调去重收据。 */
@Entity
@Table(name = "approval_callback_receipts")
@IdClass(ApprovalCallbackReceiptEntity.Pk.class)
public class ApprovalCallbackReceiptEntity {

  @Id
  @Column(length = 64, nullable = false)
  private String channel;

  @Id
  @Column(name = "callback_id", length = 255, nullable = false)
  private String callbackId;

  @Column(name = "checkpoint_id", length = 64)
  private String checkpointId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getCallbackId() {
    return callbackId;
  }

  public void setCallbackId(String callbackId) {
    this.callbackId = callbackId;
  }

  public String getCheckpointId() {
    return checkpointId;
  }

  public void setCheckpointId(String checkpointId) {
    this.checkpointId = checkpointId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  /** 复合主键。 */
  public static final class Pk implements Serializable {
    private String channel;
    private String callbackId;

    public Pk() {}

    public Pk(String channel, String callbackId) {
      this.channel = channel;
      this.callbackId = callbackId;
    }

    public String getChannel() {
      return channel;
    }

    public void setChannel(String channel) {
      this.channel = channel;
    }

    public String getCallbackId() {
      return callbackId;
    }

    public void setCallbackId(String callbackId) {
      this.callbackId = callbackId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Pk pk)) {
        return false;
      }
      return Objects.equals(channel, pk.channel) && Objects.equals(callbackId, pk.callbackId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(channel, callbackId);
    }
  }
}
