package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** workspace_asset_versions：不可变内容版本目录（049 / #473）。 */
@Entity
@Table(
    name = "workspace_asset_versions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_workspace_asset_versions",
            columnNames = {"kind", "asset_id", "version"}))
public class WorkspaceAssetVersionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String kind;

  @Column(name = "asset_id", nullable = false, length = 128)
  private String assetId;

  @Column(nullable = false)
  private long version;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "created_by", nullable = false, length = 128)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public Long getId() {
    return id;
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

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
