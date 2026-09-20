# Data model: cost ledger (#476 / 050)

## cost_ledger_entries (V24)

| Field | Notes |
|-------|-------|
| run_id | Usually TraceContext / turn id |
| task_id | Session or durable task id |
| agent_name / team_id | Attribution dimensions |
| provider / model | LLM dimensions (null for TOOL rows) |
| source_kind | LLM / TOOL |
| llm_cost_micros / tool_cost_micros | Micros; tool from config map |
| price_version | From `llm_pricing.price_version` at write time |
| latency_ms | Per event |
| session_id / trace_id | Audit join keys |

## llm_pricing.price_version

Starts at 1; `bumpPriceVersion()` on PUT update so ledger stamps are comparable across retunes.
