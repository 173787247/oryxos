package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * llm_pricing 模型定价记录（016 审计看板）——(provider, model) → 输入/输出 token 单价（元/百万 token）。 表结构以手工 schema.sql
 * 为唯一权威；prompt_price/completion_price 可空=未定价。
 */
@Entity
@Table(
    name = "llm_pricing",
    uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "model"}))
public class LlmPricing {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private String model;

  @Column(name = "prompt_price")
  private Double promptPrice;

  @Column(name = "completion_price")
  private Double completionPrice;

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
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Double getPromptPrice() {
    return promptPrice;
  }

  public void setPromptPrice(Double promptPrice) {
    this.promptPrice = promptPrice;
  }

  public Double getCompletionPrice() {
    return completionPrice;
  }

  public void setCompletionPrice(Double completionPrice) {
    this.completionPrice = completionPrice;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
