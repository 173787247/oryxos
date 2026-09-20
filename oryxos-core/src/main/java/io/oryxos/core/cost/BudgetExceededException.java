package io.oryxos.core.cost;

public class BudgetExceededException extends RuntimeException {

  public BudgetExceededException(String message) {
    super(message);
  }
}
