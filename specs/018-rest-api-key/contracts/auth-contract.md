# Contract: REST API Key 认证行为

**Feature**: 018-rest-api-key | **Date**: 2026-08-26

## 1. 请求认证契约（`oryxos.web.apikey.enabled=true` 时）

### 凭据携带方式（FR-003，二选一等效）

```
Authorization: Bearer oryx_<42位base62>
X-API-Key: oryx_<42位base62>
```

同时携带时任一有效即通过。查询参数携带不支持（不安全，进访问日志）。

### 路径裁决表（FR-002）

| 路径 / 条件 | 裁决 |
|------------|------|
| `OPTIONS` 任意路径 | 放行（CORS 预检，FR-014） |
| `/api/v1/health` | 放行（探活豁免） |
| `/api/v1/auth/**` | 放行（012 现状，端点自身校验 session/账密） |
| 其余 `/api/v1/**` + 有效 API Key | 放行 |
| 其余 `/api/v1/**` + 有效管理台 session（`oryxos_session` cookie） | 放行（FR-011） |
| 其余 `/api/v1/**` + 无凭据 / 无效 / 已吊销 Key | **401**（统一响应，见下） |
| `/admin/**`、静态资源、非 `/api/v1` 路径 | 不在本 filter 模式内，完全不受影响 |

`enabled=false`（默认）：filter 直接放行一切，行为与现状 100% 一致（FR-001）。

### 401 响应（FR-004）

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="OryxOS"
Content-Type: application/json

{"code": 401, "message": "Unauthorized", "data": null, "timestamp": ...}
```

所有失败原因（无 Key / 格式错 / 不存在 / 已吊销）返回**同一**响应体，不可区分（防探测）。服务端日志只记 Key 前缀，永不记明文。

## 2. CLI 契约（FR-005）

### `oryxos apikey add <name>`

- `<name>`：≤64 字符、无空格、全局唯一；重名 → 报错退出（非零退出码），不覆盖。
- 成功输出（明文仅此一次，FR-006）：

```
Created API key 'ci-bot':

  oryx_Xy7...42位base62...Qk

This is the ONLY time the key is displayed. Store it securely.
```

### `oryxos apikey list`

```
NAME       PREFIX          STATUS    CREATED_AT             LAST_USED_AT
ci-bot     oryx_Xy7aB2cD   active    2026-08-26T10:00:00Z   2026-08-26T12:34:56Z
report     oryx_Zw9eF4gH   revoked   2026-08-20T09:00:00Z   -
```

- 无明文；无 Key 时提示 `Run 'oryxos apikey add <name>' to create one.`

### `oryxos apikey revoke <name>`

- 成功：`Revoked API key '<name>'`，立即生效（下一次请求即 401，FR-008）。
- 名称不存在：清晰报错，非零退出码。
- 已吊销的再吊销：幂等提示（不报错不改状态）。

## 3. 配置契约（FR-001/FR-012）

```yaml
oryxos:
  web:
    apikey:
      enabled: false   # 默认关；true 时启用 /api/v1/** Key 门禁
```

启动告警（均不阻断）：

| 条件 | 行为 |
|------|------|
| `apikey.enabled=true` 且库中无有效 Key | WARN：提示 `oryxos apikey add`，此时非豁免请求全 401 |
| `apikey.enabled=true` 且 `auth.enabled=false` | WARN：管理台数据页面将不可用，建议同时开启管理台认证 |

## 4. 兼容性承诺

- `/api/v1` 全部 17 个既有子树的响应格式、语义零变更；本 feature 只加认证门，不改端点。
- 012-web-auth 的 `/admin/**` filter、`/api/v1/auth/*` 端点、`web_users`/`web_sessions` 表零改动（实现落定：`ApiKeyAuthFilter` 与 `BasicAuthFilter` 同包，package-private 的 `SESSION_COOKIE` 常量直接可见，连可见性调整都无需做）。
- OpenAPI 文档（springdoc）路径不在 `/api/v1` 下，不受影响。
