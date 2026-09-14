package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** workspace_versions：文件面变更通知总线（027）——4 域各一行只增不删；表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "workspace_versions")
public class WorkspaceVersionEntity {

  @Id
  @Column(name = "domain")
  private String domain;

  @Column(nullable = false)
  private long version;

  @Column(name = "updated_by", nullable = false)
  private String updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
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
}
