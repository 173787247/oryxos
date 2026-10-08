package io.oryxos.storage;

import io.oryxos.core.cost.AuditLlmCostSource;

public class JpaAuditLlmCostSource implements AuditLlmCostSource {

  private final LlmCallRepository repository;

  public JpaAuditLlmCostSource(LlmCallRepository repository) {
    this.repository = repository;
  }

  @Override
  public long sumCostMicrosByTraceId(String traceId) {
    if (traceId == null || traceId.isBlank()) {
      return 0L;
    }
    return repository.sumCostMicrosByTraceId(traceId);
  }
}
