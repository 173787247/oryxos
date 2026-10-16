package io.oryxos.core.cost;

import java.util.List;

/** 成本筛选器候选值（053）：枚举类维度的去重可选值，供下拉。run/task 为高基数 ID，不入 facets（文本输入）。 */
public record CostFacets(
    List<String> agents, List<String> providers, List<String> models, List<String> teams) {

  public CostFacets {
    agents = List.copyOf(agents == null ? List.of() : agents);
    providers = List.copyOf(providers == null ? List.of() : providers);
    models = List.copyOf(models == null ? List.of() : models);
    teams = List.copyOf(teams == null ? List.of() : teams);
  }

  public static CostFacets empty() {
    return new CostFacets(List.of(), List.of(), List.of(), List.of());
  }
}
