# Contract: Workspace API（027 增量）

**位置**: `oryxos-web/.../controller/WorkspaceApiController.java`（既有 `/api/v1/workspace` 挂新端点）；响应体沿统一 `ApiResponse` 格式。

## POST /api/v1/workspace/refresh — 手动刷新工作区（FR-011，运维逃生舱）

**语义**: 运维绕过管理台直接修改共享卷后，主动触发全副本重载。

**请求**: 无 body。

**响应 200**:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "mode": "cluster",            // cluster = 递增全域版本号，各副本轮询生效；
                                  // standalone = 本地立即全量重载
    "domains": ["agents", "skills", "personas", "knowledge"],
    "triggeredAt": "2026-09-14T12:00:00Z"
  },
  "timestamp": 1789387200000
}
```

**行为**:
- 集群档：`bumpWorkspaceVersion` 全部 4 域（updated_by = 本副本 owner）；本副本同 tick 内也走轮询路径生效（不特殊直呼重载，保持单一生效路径）。
- 单机档：直接本地重载（agents `reconcileAll` + skills `replaceAll` + knowledge `reconcile` 全库对账），watcher 机制不受影响。
- 鉴权：沿既有管理 API 口径（018 REST API key / 012 web-auth），无新鉴权面。

## GET /api/v1/instances（026 既有，本刀不改）

实例可见性沿 026；文件面不新增查询端点（重载指标走 Micrometer `oryxos_workspace_reloads_total{domain}`）。
