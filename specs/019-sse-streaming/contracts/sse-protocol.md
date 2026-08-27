# Contract: SSE 流式响应协议

**Feature**: 019-sse-streaming | **Date**: 2026-08-27 | 原则五：接口即承诺

## 1. 分流规则

以下三个 POST 端点按请求 `Accept` 头分流；其余端点不支持流式：

| 端点 | done 事件元数据口径 |
|------|--------------------|
| `POST /api/v1/sessions/{id}/messages` | 现有 `MessageResponse` 字段（`reply`） |
| `POST /api/v1/agents/{name}/invoke` | 同上 |
| `POST /api/v1/agents/{name}/session/messages` | 同上（管理台聊天页专用） |

- `Accept` 含 `text/event-stream` → SSE 流式响应（本契约）。
- 否则 → 与 018 交付时逐字段一致的一次性 JSON（回归零破坏，FR-001）。
- 018 认证门禁同等生效：无凭据/无效凭据 → 401 JSON（流不会开始）。

## 2. 流式响应

```
HTTP/1.1 200 OK
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache
```

标准 SSE 线格式：`event:` 行 + `data:` 行（单行 JSON）+ 空行；心跳为注释行。

### 事件类型

```
event: token
data: {"delta":"你好，"}

event: tool_start
data: {"name":"http_get"}

event: tool_end
data: {"name":"http_get","success":true}

event: done
data: {"reply":"完整回复全文"}

event: error
data: {"code":500,"message":"provider call failed"}

: ping
```

### 语义承诺

1. **终结唯一**：每次流式调用恰好一个 `done` **或**一个 `error`，此后连接关闭；调用方永远不会收到两个终结事件或无终结的正常关闭。
2. **拼接一致**：`token.delta` 按序拼接 == `done.reply`（工具调用轮 content 为空的常规情形下严格成立；个别 provider 在工具轮携带中间正文时该正文照发、不计入 `done.reply`——见 research R3）。
3. **过程有序**：`tool_start`/`tool_end` 成对出现，顺序反映真实执行顺序，可与 `token` 交错。
4. **心跳非事件**：`: ping` 注释行每 `oryxos.web.sse.heartbeat-seconds`（默认 15s）静默期出现，SSE 标准解析器自动忽略；调用方不得将其当业务事件。
5. **错误分层**：流开始前的失败（404/401/400）以普通 JSON 状态码返回；流开始后的失败只能以 `error` 事件表达（HTTP 已是 200）。
6. **断开语义**：客户端断开后服务端停止推送，但本轮处理照常完成并落库、审计照写——**断开不退款**（已消耗与将继续消耗的 token 照常计入审计）。
7. **审计不变**：流式与非流式对同一消息写入完全相同口径的 `llm_calls` / `tool_invocations`。

## 3. 调用示例

```bash
# curl（-N 关闭缓冲）
curl -N -H "Accept: text/event-stream" -H "X-API-Key: $KEY" \
  -H "Content-Type: application/json" -d '{"content":"介绍一下你自己"}' \
  http://localhost:8080/api/v1/sessions/$SID/messages
```

```js
// 浏览器（EventSource 不支持 POST，用 fetch + ReadableStream）
const res = await fetch(url, { method: 'POST', headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json' }, body });
const reader = res.body.getReader(); // 按 SSE 行协议解析 event/data 对
```

## 4. CLI 行为契约（FR-015）

`oryxos chat` 进程内消费同一套流式能力（不经 HTTP）：回复逐段打印；工具调用期间单行状态提示；Provider 无流式能力时自动回落为整段输出（不报错、无感知差异）。

## 5. 兼容性承诺

- 非流式路径（不带 `Accept: text/event-stream`）的响应体、状态码、异常口径与 018 交付时逐字段一致。
- 事件类型与负载字段只增不改不删；新增事件类型时旧客户端可安全忽略未知 `event:`。
- `/api/v1` 其余端点、CLI 其余命令、审计表结构零变化。
