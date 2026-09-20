package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 用户 ↔ 团队成员关系（#535 / #462）。表结构以 db/migration V12 为唯一权威。
 *
 * <p>{@code team_id} 为不透明字符串，与 OIDC groups / 资产 {@code teamOwner} 同一命名空间；无独立 teams 目录表。
 */
@Entity
@Table(
    name = "team_memberships",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_team_memberships_user_team",
            columnNames = {"username", "team_id"}))
public class TeamMembership {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String username;

  @Column(name = "team_id", nullable = false, length = 128)
  private String teamId;

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

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getTeamId() {
    return teamId;
  }

  public void setTeamId(String teamId) {
    this.teamId = teamId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
