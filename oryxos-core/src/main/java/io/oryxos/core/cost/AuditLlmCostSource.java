package io.oryxos.core.cost;

/** Reads write-time LLM costs from audit (llm_calls) for reconcile (#476). */
public interface AuditLlmCostSource {

  /** Sum of llm_calls.cost_micros for the given run/trace id (null costs count as 0). */
  long sumCostMicrosByTraceId(String traceId);

  AuditLlmCostSource NOOP = traceId -> 0L;
}
