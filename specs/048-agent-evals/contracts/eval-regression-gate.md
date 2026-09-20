# Contract: Eval harness & regression gate（048 / #472）

## Flags

| Property | Default | Effect |
|----------|---------|--------|
| `oryxos.eval.gate-enabled` | `false` | When false, runtime must not block boot/publish |
| `oryxos.eval.min-success-rate` | `0.80` | Absolute floor |
| `oryxos.eval.min-tool-accuracy` | `0.80` | Absolute floor |
| `oryxos.eval.min-citation-quality` | `0.70` | Absolute floor |
| `oryxos.eval.max-latency-ms-avg` | `10000` | Absolute ceiling |
| `oryxos.eval.max-cost-micros-total` | `5000000` | Absolute ceiling |
| `oryxos.eval.max-rate-regression` | `0.05` | Max drop vs baseline (rates) |
| `oryxos.eval.max-latency-regression-ms` | `2000` | Max latency increase vs baseline |

## API (`io.oryxos.core.eval`)

1. `EvalHarness.score(cases)` → `EvalMetrics`
2. `EvalHarness.compare(current, baseline)` → `EvalMetricsDelta`
3. `EvalHarness.evaluate(name, cases, baseline?)` → `EvalSuiteResult`
4. `EvalRegressionGate.evaluate(metrics, thresholds, baseline?, …)` → `EvalGateDecision`
5. `EvalFixtureLoader.loadSuiteFromClasspath` / `loadSuite(Path)` — JSON fixtures

## CI / release

- Unit tests under `oryxos-core` always exercise scoring + gate (CI via `mvn verify`)
- Optional release gate: `scripts/eval-regression-gate.sh` (exit 1 on fail)
