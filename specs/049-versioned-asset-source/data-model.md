# Data model: versioned asset source (V23 / #473)

## workspace_asset_versions

Immutable content version catalog (one row per published snapshot).

| Column | Notes |
|--------|--------|
| id | Surrogate PK |
| kind | `agents` / `skills` / `knowledge` |
| asset_id | Safe name (Agent / Skill / KB id) |
| version | Monotonic per (kind, asset_id), starting at 1 |
| content_hash | SHA-256 hex of snapshot payload fingerprint |
| created_by | Actor string |
| created_at | DB timestamp |

Unique `(kind, asset_id, version)`.

## workspace_asset_active

Shared active pointer (authority for “determined version”).

| Column | Notes |
|--------|--------|
| kind + asset_id | PK |
| active_version | Currently live version |
| previous_version | Prior active (nullable) — enables one-step rollback |
| updated_by / updated_at | Audit |

## File carrier

```
{oryxos.root}/.asset-versions/{kind}/{asset_id}/v{n}/...
```

Live trees remain `{root}/agents|skills|knowledge/{asset_id}/`. Activate materializes snapshot → live via staging + atomic directory rename, then bumps the matching `workspace_versions` domain.
