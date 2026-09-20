# Contract: versioned asset source API (#473)

Flag: `oryxos.cluster.versioned-asset-source-enabled` (default `false`). When false, all endpoints
return **404**.

Base: `/api/v1/workspace/assets/{kind}/{assetId}` where `kind` ∈ `agents|skills|knowledge`.

| Method | Path | Behavior |
|--------|------|----------|
| GET | `/active` | `{ version }` of shared active pointer; 404 if none |
| GET | `/versions` | List published versions (newest first) |
| POST | `/versions` | Publish live tree as next version; first publish also activates |
| POST | `/versions/{version}/activate` | Atomic switch: materialize + pointer + bump domain |
| POST | `/rollback` | Activate `previous_version`; 404 if none |

Core library: `io.oryxos.core.workspace.versioned.VersionedAssetSource`.
