package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 资产治理全文快照（#537）。表结构以 db/migration V13 为唯一权威。追加型——无更新/删除业务路径。 */
@Entity
@Table(name = "asset_governance_revisions")
public class AssetGovernanceRevision {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "resource_type", nullable = false, length = 32)
  private String resourceType;

  @Column(name = "resource_id", nullable = false, length = 255)
  private String resourceId;

  @Column(name = "version_label", length = 128)
  private String versionLabel;

  @Lob
  @Column(name = "snapshot_text", nullable = false)
  private String snapshotText;

  @Column(nullable = false, length = 128)
  private String actor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public void setResourceId(String resourceId) {
    this.resourceId = resourceId;
  }

  public String getVersionLabel() {
    return versionLabel;
  }

  public void setVersionLabel(String versionLabel) {
    this.versionLabel = versionLabel;
  }

  public String getSnapshotText() {
    return snapshotText;
  }

  public void setSnapshotText(String snapshotText) {
    this.snapshotText = snapshotText;
  }

  public String getActor() {
    return actor;
  }

  public void setActor(String actor) {
    this.actor = actor;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
