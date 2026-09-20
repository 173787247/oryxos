# Acceptance report — 048 / #472

| Acceptance | Evidence |
|------------|----------|
| 可度量成功率、工具正确率、引用质量、时延和成本 | `EvalHarnessTest.score_aggregatesAcceptanceMetrics` |
| 变更可对比基线 | `EvalHarnessTest.compare_producesDeltasVsBaseline` + sample suite baseline |
| 未达门槛可阻止发布 | `EvalRegressionGateTest.gate_blocksWhenBelowThresholds` + baseline regression test |
| Agent/Skill/Prompt/Flow fixtures | `EvalFixtureLoaderTest` + `/evals/sample-suite.json` |
| Default-off runtime flag | `EvalProperties.gateEnabled=false` |
