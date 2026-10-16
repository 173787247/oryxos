package io.oryxos.core.cost;

import java.util.List;

/** Append-only cost ledger persistence (#476). */
public interface CostLedgerStore {

  CostLedgerEntry append(CostLedgerEntry entry);

  List<CostLedgerEntry> find(CostAttributionQuery query);

  long sumLlmCostMicros(CostAttributionQuery query);

  long sumToolCostMicros(CostAttributionQuery query);

  /** 枚举类维度的去重候选值（053 筛选器下拉）。 */
  CostFacets facets();
}
