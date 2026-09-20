# Acceptance report: #467 Markdown Flow DSL + static validation

| Criterion | Evidence |
|-----------|----------|
| 运行前发现引用缺失 | `FlowValidatorTest.unknownEdgeTarget_reports`; `UNKNOWN_NODE_REF` |
| 运行前发现类型不兼容 | `FlowValidatorTest.typeMismatch_reports`; `TYPE_MISMATCH` |
| 运行前发现循环依赖 | `FlowValidatorTest.cycle_reports`; `CYCLE_DETECTED` |
| DSL 可 Git diff/review | `.flow.md` frontmatter graph + Markdown body; examples under `specs/045-markdown-flow-dsl/examples/` |
| 最小可执行示例 | `hello-notify.flow.md` / `branch-approve.flow.md` parse+validate in `FlowDocumentsTest` |
| Default-off / library | `io.oryxos.core.flow` only — no boot wiring |

#468 durable engine and #469 human wait/compensate remain out of scope.
