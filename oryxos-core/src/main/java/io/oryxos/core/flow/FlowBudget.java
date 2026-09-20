package io.oryxos.core.flow;

/** Optional resource budget declared on a Flow. */
public record FlowBudget(Integer maxDurationSeconds, Integer maxToolCalls, Integer maxTokens) {

  public static final FlowBudget EMPTY = new FlowBudget(null, null, null);
}
