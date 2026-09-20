# Data model: model routing (#477 / 051)

## RoutingDecision (in-memory ring buffer)

| Field | Notes |
|-------|-------|
| id | UUID when recorded |
| runId | TraceContext / CostContext run id |
| agentName | Profile name |
| selectedProvider / selectedModel | First attempt after strategy |
| reasons[] | code + detail (DIFFICULTY, SENSITIVITY_RESIDENCY, BUDGET, LATENCY, COST_PREFER_CHEAP, FALLBACK, DEFAULT) |
| candidates[] | SELECTED / ORDERED / FILTERED dispositions |
| attemptOrder[] | Final try sequence for this call |
| createdAt | Instant |

Thin cut: no DB table; bounded `InMemoryRoutingDecisionStore` (config `decision-log-size`).
