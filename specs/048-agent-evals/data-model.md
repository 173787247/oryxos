# Data model: eval fixtures (#472 / 048)

## EvalCase

| Field | Type | Notes |
|-------|------|-------|
| id | string | Stable case id |
| kind | AGENT / SKILL / PROMPT / FLOW | Target surface |
| name | string | Human label |
| success | boolean | Case-level success |
| expectedTools / actualTools | string[] | Tool accuracy = intersection / expected size |
| expectedCitations / actualCitations | string[] | Citation quality (same formula) |
| latencyMs | long | Observed latency |
| costMicros | long | Observed cost (audit-compatible unit) |

## EvalMetrics (suite aggregate)

| Field | Aggregation |
|-------|-------------|
| successRate | successes / n |
| toolAccuracy | mean overlap over cases with non-empty expectedTools (else 1.0) |
| citationQuality | mean overlap over cases with non-empty expectedCitations (else 1.0) |
| latencyMsAvg | mean latencyMs |
| costMicrosTotal | sum costMicros |

## EvalBaseline

Named snapshot of `EvalMetrics` (+ `suiteName`, `capturedAt`) for regression deltas.
