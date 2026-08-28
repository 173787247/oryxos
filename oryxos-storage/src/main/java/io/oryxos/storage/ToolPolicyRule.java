package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 工具策略规则（020-tool-policy）——表结构以手工 schema.sql 为唯一权威。
 *
 * <p>rule_type：GLOBAL_DENY（agent_name 为空）/ AGENT_EXEMPT / AGENT_DENY；pattern 为工具精确名或 MCP server
 * 通配（server:*）。created_by 记录规则来源（配置即责任的最低追溯口径）。
 */
@Entity
@Table(name = "tool_policy_rules")
public class ToolPolicyRule {

  /** 规则类型（枚举字面量入库，镜像 sandbox_whitelist 的 category 存法）。 */
  public enum RuleType {
    GLOBAL_DENY,
    AGENT_EXEMPT,
    AGENT_DENY
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "rule_type", nullable = false)
  private String ruleType;

  @Column(name = "agent_name")
  private String agentName;

  @Column(name = "pattern", nullable = false)
  private String pattern;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private String createdBy;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public String getRuleType() {
    return ruleType;
  }

  public void setRuleType(String ruleType) {
    this.ruleType = ruleType;
  }

  public String getAgentName() {
    return agentName;
  }

  public void setAgentName(String agentName) {
    this.agentName = agentName;
  }

  public String getPattern() {
    return pattern;
  }

  public void setPattern(String pattern) {
    this.pattern = pattern;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }
}
