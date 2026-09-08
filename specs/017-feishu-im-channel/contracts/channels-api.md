# Contract: 渠道管理 REST API（`/api/v1/channels`）

**Feature**: 017 | **满足**: FR-013（免重启变更）、FR-014（状态可见）| **形态**: 复刻 `McpApiController`（`/api/v1/mcp-servers`）的既有口径，统一 `ApiResponse` 包装。

所有写操作走 `ChannelAdminService`（`synchronized`：校验 → 落盘 channels.yaml → 先 disconnect 旧连接 → connect 新配置），无需重启进程。CRUD 读写一律用 `loadRaw()` 字面量（`${ENV}` 占位原样进出，永不回显明文凭证）。

| 方法 | 路径 | 说明 | 成功 | 失败 |
|------|------|------|------|------|
| `GET` | `/api/v1/channels` | 列渠道（raw 配置，app_secret 掩码显示） | `ApiResponse<List<ChannelView>>` | — |
| `GET` | `/api/v1/channels/status` | 渠道在线状态（实况） | `ApiResponse<List<ChannelStatusView>>` | — |
| `POST` | `/api/v1/channels` | 新增渠道并立即上线 | `ApiResponse<ChannelView>` | 400 点名校验错误（名称冲突/类型不支持/凭证未解析/Agent 不存在） |
| `PUT` | `/api/v1/channels/{name}` | 更新（先断旧再建新） | `ApiResponse<ChannelView>` | 404 不存在；400 校验错误 |
| `DELETE` | `/api/v1/channels/{name}` | 断开并移除配置 | `ApiResponse<Void>` | 404 不存在 |

## DTO

```jsonc
// ChannelView（GET 列表 / 写操作回显；app_secret 永远掩码或保留 ${} 字面量）
{ "name": "ops-feishu", "type": "feishu", "appId": "cli_xxx",
  "appSecret": "${FEISHU_APP_SECRET}", "agent": "ops-agent", "enabled": true }

// ChannelStatusView
{ "name": "ops-feishu", "type": "feishu", "agent": "ops-agent",
  "state": "CONNECTED", "error": null }

// ChannelRequest（POST/PUT 入参；appSecret 允许 ${ENV} 字面量，推荐必用）
{ "name": "ops-feishu", "type": "feishu", "appId": "${FEISHU_APP_ID}",
  "appSecret": "${FEISHU_APP_SECRET}", "agent": "ops-agent", "enabled": true }
```

## 错误文案口径（SC-008）

沿用 Provider 校验的点名风格：`渠道 ops-feishu 的 app_secret 未配置或环境变量未解析，请检查 FEISHU_APP_SECRET`；`渠道 ops-feishu 绑定的 Agent foo 不存在`。校验失败的渠道不上线，但列表与 status 可见（state=ERROR + error 原因），其余渠道与功能不受影响。
