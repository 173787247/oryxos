package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** workspace_asset_active：共享 active / previous 指针（049 / #473）。 */
@Entity
@Table(name = "workspace_asset_active")
@IdClass(WorkspaceAssetActiveEntity.Pk.class)
public class WorkspaceAssetActiveEntity {

  @Id
  @Column(nullable = false, length = 32)
  private String kind;

  @Id
  @Column(name = "asset_id", nullable = false, length = 128)
  private String assetId;

  @Column(name = "active_version", nullable = false)
  private long activeVersion;

  @Column(name = "previous_version")
  private Long previousVersion;

  @Column(name = "updated_by", nullable = false, length = 128)
  private String updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getAssetId() {
    return assetId;
  }

  public void setAssetId(String assetId) {
    this.assetId = assetId;
  }

  public long getActiveVersion() {
    return activeVersion;
  }

  public void setActiveVersion(long activeVersion) {
    this.activeVersion = activeVersion;
  }

  public Long getPreviousVersion() {
    return previousVersion;
  }

  public void setPreviousVersion(Long previousVersion) {
    this.previousVersion = previousVersion;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public static final class Pk implements Serializable {
    private static final long serialVersionUID = 1L;

    private String kind;
    private String assetId;

    public Pk() {}

    public Pk(String kind, String assetId) {
      this.kind = kind;
      this.assetId = assetId;
    }

    public String getKind() {
      return kind;
    }

    public void setKind(String kind) {
      this.kind = kind;
    }

    public String getAssetId() {
      return assetId;
    }

    public void setAssetId(String assetId) {
      this.assetId = assetId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Pk pk)) {
        return false;
      }
      return Objects.equals(kind, pk.kind) && Objects.equals(assetId, pk.assetId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, assetId);
    }
  }
}
