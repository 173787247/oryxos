package io.oryxos.core.cost;

/** Over-budget policy (#476): block call, or degrade to a cheaper configured model. */
public enum OverBudgetAction {
  BLOCK,
  DEGRADE
}
