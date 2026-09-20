# Contract: Markdown Flow DSL + static validation（045 / #467）

## 1. Parse

`FlowMarkdown.parse(markdown)`:

1. Split via `AgentMarkdown.split` (frontmatter YAML + body)
2. Require `apiVersion: oryxos.flow/v1` and `kind: Flow` (kind defaulted to Flow if omitted)
3. Map YAML to immutable `FlowDefinition` (body becomes `description`)

Malformed YAML / non-map frontmatter raises `IllegalArgumentException` (parse error, not lint).

## 2. Validate

`FlowValidator.validate(definition)` returns zero-or-more `FlowDiagnostic` (ERROR severity for
acceptance-blocking issues). Does **not** execute nodes.

Callers treat any ERROR as fail-closed for ship/CI lint.

## 3. Compatibility (ports)

| Source \ Target | string | number | boolean | object | any |
|-----------------|--------|--------|---------|--------|-----|
| string | yes | | | | yes |
| number | | yes | | | yes |
| boolean | | | yes | | yes |
| object | | | | yes | yes |
| any | yes | yes | yes | yes | yes |

## 4. Library boundary

Package `io.oryxos.core.flow` is pure POJO + SnakeYAML via existing `AgentMarkdown`.
No Spring `@Component`, no `application.yml` flag required for this cut (nothing is wired).

## 5. Forward hooks (not implemented here)

- Node types `human` / `approval` reserved for #469
- Runtime interpreter / checkpointed execution for #468
