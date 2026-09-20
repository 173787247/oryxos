# Acceptance report: 049-versioned-asset-source (#473)

**Date**: 2026-09-20  
**Verdict**: Thin cut covers remaining #473 acceptance on top of PR #483 shared half.

| Acceptance | Evidence |
|------------|----------|
| 多实例读取同一确定版本 | `VersionedAssetSourceTest` dual-reader same `activeVersion` after activate |
| 更新可原子切换并回滚 | publish → edit live → publish → activate v1 / `rollback` restores prior content |
| 不依赖本地节点目录唯一真相 | Active pointer in DB tables V23; snapshots under `.asset-versions/`; live is materialization |
| Default-off | `oryxos.cluster.versioned-asset-source-enabled=false`; APIs 404 when off |

Shared half (027 / #483) unchanged: visibility poller, AtomicFiles, knowledge claims.
