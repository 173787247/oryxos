package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 团队目录行（#539 / #554 / #581）。表结构以 db/migration V14+V15+V17 为唯一权威。
 *
 * <p>{@code team_id} 与 OIDC groups / {@code teamOwner} / {@code team_memberships}
 * 同一字符串空间；本表只存展示名、可选 {@code org_id} 与可选 {@code parent_team_id}。
 */
@Entity
@Table(name = "teams")
public class Team {

  @Id
  @Column(name = "team_id", nullable = false, length = 128)
  private String teamId;

  @Column(name = "display_name", nullable = false, length = 255)
  private String displayName;

  /** 可选所属组织；空=未挂组织。不参与授权裁决。 */
  @Column(name = "org_id", length = 128)
  private String orgId;

  /** 可选父团队；空=根。不参与授权裁决。 */
  @Column(name = "parent_team_id", length = 128)
  private String parentTeamId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public String getTeamId() {
    return teamId;
  }

  public void setTeamId(String teamId) {
    this.teamId = teamId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getOrgId() {
    return orgId;
  }

  public void setOrgId(String orgId) {
    this.orgId = orgId;
  }

  public String getParentTeamId() {
    return parentTeamId;
  }

  public void setParentTeamId(String parentTeamId) {
    this.parentTeamId = parentTeamId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
