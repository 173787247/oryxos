# Feature Specification: Markdown Flow DSL + static validation

**Feature Branch**: `feat/467-markdown-flow-dsl`

**Created**: 2026-09-20

**Status**: First cut (design / static-check — not the durable engine)

**Tracks**: #467 (epic #456)

## Intent

Versioned, Git-friendly Flow definitions in Markdown (YAML frontmatter + prose body):
node contracts, typed ports, edges/branches, dependencies, permissions, and resource budgets.
Static validation catches broken graphs **before** any runtime execution.

This cut is the Flow epic **DSL + lint** layer. It does **not** persist or execute flows (#468)
or implement human-node wait/compensate/replay (#469).

## Hard constraints

- Library-only under `io.oryxos.core.flow` — no Spring auto-wiring, no boot runtime path
- Zero behavior change for Agent runs until a later cut opts in
- Human / approval node **types** may appear in the schema for forward compatibility; execution stays #468/#469
- Reuse `AgentMarkdown` frontmatter split so Flow files stay reviewable like `AGENT.md`

## Acceptance mapping (#467)

| Acceptance | This cut |
|------------|----------|
| 运行前发现引用缺失、类型不兼容、循环依赖 | `FlowValidator` diagnostics: `UNKNOWN_*` / `TYPE_MISMATCH` / `CYCLE_DETECTED` |
| DSL 可 Git diff/review | `.flow.md` = YAML frontmatter graph + Markdown body narrative |
| 最小可执行示例 | `specs/045-markdown-flow-dsl/examples/*.flow.md` parse + validate clean in tests |

## Out of scope

- Durable Flow execution engine / resume / idempotent node runs (#468)
- Human wait / compensate / timeline replay (#469)
- Admin UI / IM triggers for Flows
- Full Speckit 九件套 (research/plan/tasks)

## Key types

- `FlowDefinition` / `FlowNode` / `FlowEdge` / `FlowPort` / `FlowBudget` / `FlowPermissions`
- `FlowMarkdown.parse` → definition
- `FlowValidator.validate` → `List<FlowDiagnostic>`
- `FlowDocuments.parseAndValidate` convenience
