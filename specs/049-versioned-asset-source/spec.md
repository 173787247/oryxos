# Feature Specification: Versioned Agent / Skill / Knowledge source (#473)

**Feature Branch**: `feat/473-versioned-asset-source`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #473 (epic #458); completes remaining acceptance after PR #483 / 027 shared half

## Intent

On top of 027 (shared volume + `workspace_versions` notify bus), define a **content-versioned**
authoritative source for Agent / Skill / Knowledge assets: immutable snapshots, a shared active
pointer, atomic activate, and rollback — so cluster replicas agree on one determined version and
updates can switch / roll back without treating a single node’s live directory as sole truth.

## Hard constraints

- `oryxos.cluster.versioned-asset-source-enabled` default **false** (zero runtime behavior change)
- Reuse AtomicFiles + workspace version bump; do not replace 027 notify bus
- Snapshots live under shared workspace `.asset-versions/` (file carrier); active pointer in DB
- No Admin UI / diff browser in this cut

## Acceptance mapping (#473)

| Acceptance | Coverage |
|------------|----------|
| 多实例读取同一确定版本 | Shared DB active pointer + shared-volume snapshots; readers call `activeVersion` |
| 更新可原子切换并回滚 | `activate(version)` + `rollback()` (previous pointer) + live dir atomic rename |
| 不依赖本地节点目录作为唯一真相 | Authority = DB pointer + `.asset-versions/` on shared root (027 shared half retained) |

## Shared half (already delivered)

PR #483 / 027: shared volume, `workspace_versions` poller, AtomicFiles, knowledge generations.
This cut does **not** re-implement visibility / poller / index claims.

## Out of scope

- Auto-publish on every Agent/Skill edit (opt-in `publish` only)
- Governance YAML history (#537/#541) — orthogonal metadata plane
- Object-store / Git carriers; multi-tenant keys
