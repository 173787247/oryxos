package io.oryxos.core.cost;

public record BudgetDecision(
    boolean allowed,
    OverBudgetAction action,
    String reason,
    String degradeProvider,
    String degradeModel) {

  public static BudgetDecision allow() {
    return new BudgetDecision(true, null, null, null, null);
  }

  public static BudgetDecision block(String reason) {
    return new BudgetDecision(false, OverBudgetAction.BLOCK, reason, null, null);
  }

  public static BudgetDecision degrade(String reason, String provider, String model) {
    return new BudgetDecision(true, OverBudgetAction.DEGRADE, reason, provider, model);
  }

  public boolean isDegrade() {
    return action == OverBudgetAction.DEGRADE;
  }
}
