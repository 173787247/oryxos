package io.oryxos.core.policy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 审批策略配置（042 / #464）：{@code oryxos.approval.*}。默认 {@code enabled=false} 零行为变化。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "Spring ConfigurationProperties binders require mutable list getters/setters.")
@ConfigurationProperties(prefix = "oryxos.approval")
public class ApprovalPolicyProperties {

  /** 总开关。默认关。 */
  private boolean enabled = false;

  /** 策略版本（审计字段）。 */
  private String policyVersion = "1";

  /** 规则未写 ttl 时的默认审批有效期（秒）。 */
  private int defaultTtlSeconds = 3600;

  /** 规则未写 approvers 时的默认审批人。 */
  private List<String> defaultApprovers = new ArrayList<>();

  private List<RuleProperties> rules = new ArrayList<>();

  public ApprovalPolicyConfig toConfig() {
    List<ApprovalPolicyConfig.Rule> mapped = new ArrayList<>();
    for (RuleProperties r : rules) {
      if (r == null) {
        continue;
      }
      List<HighRiskActionType> types = new ArrayList<>();
      for (String raw : r.getActionTypes()) {
        HighRiskActionType t = parseActionType(raw);
        if (t != null) {
          types.add(t);
        }
      }
      mapped.add(
          new ApprovalPolicyConfig.Rule(
              r.getId(),
              r.getAgents(),
              r.getTools(),
              types,
              r.getApprovers(),
              r.getTtlSeconds(),
              ApprovalPolicyConfig.parseEffect(r.getEffect()),
              ApprovalPolicyConfig.parseDenySemantics(
                  r.getOnTimeout(), ApprovalDenySemantics.TIMEOUT_REJECT),
              ApprovalPolicyConfig.parseDenySemantics(
                  r.getOnDeny(), ApprovalDenySemantics.REJECT)));
    }
    return new ApprovalPolicyConfig(
        enabled, policyVersion, defaultTtlSeconds, defaultApprovers, mapped);
  }

  private static HighRiskActionType parseActionType(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return HighRiskActionType.valueOf(ApprovalPolicyConfig.asciiUpperSnake(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public void setPolicyVersion(String policyVersion) {
    this.policyVersion = policyVersion;
  }

  public int getDefaultTtlSeconds() {
    return defaultTtlSeconds;
  }

  public void setDefaultTtlSeconds(int defaultTtlSeconds) {
    this.defaultTtlSeconds = defaultTtlSeconds;
  }

  public List<String> getDefaultApprovers() {
    return defaultApprovers;
  }

  public void setDefaultApprovers(List<String> defaultApprovers) {
    this.defaultApprovers = defaultApprovers == null ? new ArrayList<>() : defaultApprovers;
  }

  public List<RuleProperties> getRules() {
    return rules;
  }

  public void setRules(List<RuleProperties> rules) {
    this.rules = rules == null ? new ArrayList<>() : rules;
  }

  /** YAML 规则条目。 */
  public static class RuleProperties {
    private String id = "unnamed";
    private List<String> agents = new ArrayList<>();
    private List<String> tools = new ArrayList<>();
    private List<String> actionTypes = new ArrayList<>();
    private List<String> approvers = new ArrayList<>();
    private Integer ttlSeconds;

    /** require_approval | deny */
    private String effect = "require_approval";

    private String onTimeout = "timeout_reject";
    private String onDeny = "reject";

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public List<String> getAgents() {
      return agents;
    }

    public void setAgents(List<String> agents) {
      this.agents = agents == null ? new ArrayList<>() : agents;
    }

    public List<String> getTools() {
      return tools;
    }

    public void setTools(List<String> tools) {
      this.tools = tools == null ? new ArrayList<>() : tools;
    }

    public List<String> getActionTypes() {
      return actionTypes;
    }

    public void setActionTypes(List<String> actionTypes) {
      this.actionTypes = actionTypes == null ? new ArrayList<>() : actionTypes;
    }

    public List<String> getApprovers() {
      return approvers;
    }

    public void setApprovers(List<String> approvers) {
      this.approvers = approvers == null ? new ArrayList<>() : approvers;
    }

    public Integer getTtlSeconds() {
      return ttlSeconds;
    }

    public void setTtlSeconds(Integer ttlSeconds) {
      this.ttlSeconds = ttlSeconds;
    }

    public String getEffect() {
      return effect;
    }

    public void setEffect(String effect) {
      this.effect = effect;
    }

    public String getOnTimeout() {
      return onTimeout;
    }

    public void setOnTimeout(String onTimeout) {
      this.onTimeout = onTimeout;
    }

    public String getOnDeny() {
      return onDeny;
    }

    public void setOnDeny(String onDeny) {
      this.onDeny = onDeny;
    }
  }
}
