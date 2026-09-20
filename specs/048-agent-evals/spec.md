# Feature Specification: Agent / Skill / Prompt / Flow evals & regression gate

**Feature Branch**: `feat/472-agent-skill-prompt-flow-evals`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #472 (epic #457); follows #470 model + #471 OTel

## Intent

Define a small offline eval harness: fixtures, scoring (success / tool accuracy / citation
quality / latency / cost), baseline comparison, and a regression gate that can block release —
without building a full MLOps / langfuse platform.

## Hard constraints

- `oryxos.eval.gate-enabled` default **false** (zero runtime behavior change)
- Pure library in `oryxos-core` (`io.oryxos.core.eval`) — no new Maven module
- Fixtures are recorded observations; harness does not call LLM providers in this cut
- CI-friendly: unit tests + `scripts/eval-regression-gate.sh`

## Acceptance mapping (#472)

| Acceptance | This cut |
|------------|----------|
| 可度量成功率、工具正确率、引用质量、时延和成本 | `EvalMetrics` via `EvalHarness.score` |
| 变更可对比基线 | `EvalBaseline` + `EvalMetricsDelta` / embedded suite baseline |
| 未达门槛可阻止发布 | `EvalRegressionGate` + opt-in flag + gate script |

## Out of scope

- Live Agent replay against production traces
- Admin console eval UI / dataset management
- Auto-prompt optimization / data flywheel writers
- New OTel span types (covered by #470/#471)
