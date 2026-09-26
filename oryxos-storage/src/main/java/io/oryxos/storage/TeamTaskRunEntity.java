package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** team_task_runs：Direction I 团队任务运行持久化。 */
@Entity
@Table(name = "team_task_runs")
public class TeamTaskRunEntity {

  @Id
  @Column(length = 64)
  private String id;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String goal;

  @Column(nullable = false)
  private String coordinator;

  @Column(name = "plan_raw", columnDefinition = "TEXT")
  private String planRaw;

  @Column(name = "workers_json", nullable = false, columnDefinition = "TEXT")
  private String workersJson;

  @Column(columnDefinition = "TEXT")
  private String summary;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getGoal() {
    return goal;
  }

  public void setGoal(String goal) {
    this.goal = goal;
  }

  public String getCoordinator() {
    return coordinator;
  }

  public void setCoordinator(String coordinator) {
    this.coordinator = coordinator;
  }

  public String getPlanRaw() {
    return planRaw;
  }

  public void setPlanRaw(String planRaw) {
    this.planRaw = planRaw;
  }

  public String getWorkersJson() {
    return workersJson;
  }

  public void setWorkersJson(String workersJson) {
    this.workersJson = workersJson;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
