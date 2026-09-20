package io.oryxos.core.cost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cost ledger / budget flags (#476). Defaults keep runtime unchanged.
 *
 * <pre>
 * oryxos.cost.enabled=false
 * oryxos.cost.enforcement-enabled=false
 * oryxos.cost.over-budget-action=block
 * </pre>
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "Spring ConfigurationProperties binders require mutable getters/setters.")
@ConfigurationProperties(prefix = "oryxos.cost")
public class CostProperties {

  /** Master switch for ledger recording + query APIs. Default off. */
  private boolean enabled = false;

  /** When true (and enabled), enforce budgets before LLM/tool. Default off. */
  private boolean enforcementEnabled = false;

  private OverBudgetAction overBudgetAction = OverBudgetAction.BLOCK;

  /** Cheap model override used when action=DEGRADE. */
  private String degradeProvider = "";

  private String degradeModel = "";

  /** Flat tool cost micros by tool name; missing keys use defaultToolCostMicros. */
  private long defaultToolCostMicros = 0L;

  private Map<String, Long> toolCosts = new HashMap<>();

  private List<BudgetRule> budgets = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isEnforcementEnabled() {
    return enforcementEnabled;
  }

  public void setEnforcementEnabled(boolean enforcementEnabled) {
    this.enforcementEnabled = enforcementEnabled;
  }

  public OverBudgetAction getOverBudgetAction() {
    return overBudgetAction;
  }

  public void setOverBudgetAction(OverBudgetAction overBudgetAction) {
    this.overBudgetAction = overBudgetAction == null ? OverBudgetAction.BLOCK : overBudgetAction;
  }

  public String getDegradeProvider() {
    return degradeProvider;
  }

  public void setDegradeProvider(String degradeProvider) {
    this.degradeProvider = degradeProvider == null ? "" : degradeProvider;
  }

  public String getDegradeModel() {
    return degradeModel;
  }

  public void setDegradeModel(String degradeModel) {
    this.degradeModel = degradeModel == null ? "" : degradeModel;
  }

  public long getDefaultToolCostMicros() {
    return defaultToolCostMicros;
  }

  public void setDefaultToolCostMicros(long defaultToolCostMicros) {
    this.defaultToolCostMicros = Math.max(0L, defaultToolCostMicros);
  }

  public Map<String, Long> getToolCosts() {
    return toolCosts;
  }

  public void setToolCosts(Map<String, Long> toolCosts) {
    this.toolCosts = toolCosts == null ? new HashMap<>() : toolCosts;
  }

  public List<BudgetRule> getBudgets() {
    return budgets;
  }

  public void setBudgets(List<BudgetRule> budgets) {
    this.budgets = budgets == null ? new ArrayList<>() : budgets;
  }

  public long toolCostMicros(String toolName) {
    if (toolName != null && toolCosts.containsKey(toolName)) {
      Long v = toolCosts.get(toolName);
      return v == null ? defaultToolCostMicros : Math.max(0L, v);
    }
    return defaultToolCostMicros;
  }

  public static class BudgetRule {
    private String id = "";
    private BudgetScope scope = BudgetScope.AGENT;

    /** Scope key: agent name / team id / model / run id; blank = match any in that scope. */
    private String key = "";

    private long limitMicros = 0L;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id == null ? "" : id;
    }

    public BudgetScope getScope() {
      return scope;
    }

    public void setScope(BudgetScope scope) {
      this.scope = scope == null ? BudgetScope.AGENT : scope;
    }

    public String getKey() {
      return key;
    }

    public void setKey(String key) {
      this.key = key == null ? "" : key;
    }

    public long getLimitMicros() {
      return limitMicros;
    }

    public void setLimitMicros(long limitMicros) {
      this.limitMicros = Math.max(0L, limitMicros);
    }
  }
}
