# Data Model: SSE 流式响应

**Feature**: 019-sse-streaming | **Date**: 2026-08-27

**无新表、无表结构变更**——流事件是纯传输态，不落库；落库的仍是既有会话历史（`sessions.messages_json`）与审计（`llm_calls`/`tool_invocations`），口径零变化（FR-012）。

## 核心契约（oryxos-core，依赖倒置）

### StreamListener（新接口，`core/agent/`）

一次消息处理过程中的流式观察者。全部方法同步回调、在处理线程上执行；实现方自行保证快速返回（写出阻塞属于消费面自身的语义）。

| 方法 | 触发时机 | 参数 |
|------|---------|------|
| `onToken(String delta)` | Provider 流出一段回复文本增量 | 非空文本增量 |
| `onToolStart(String toolName)` | ReActLoop 即将执行一个工具调用 | 工具名 |
| `onToolEnd(String toolName, boolean success)` | 工具调用返回 | 工具名、成败 |

- 常量 `StreamListener.NOOP`：全空实现；既有非流式路径统一走 NOOP，保证单一代码路径。
- 不含 `onDone`/`onError`：终结语义由调用方（controller/CLI）掌握——`AgentService` 正常返回即 done、抛异常即 error，无需重复进 listener。

### ProviderService（core 契约扩展）

```java
// 既有
ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
// 新增：流式调用——onToken 逐段回调 content 增量，返回值仍是完整响应（文本/toolCalls/usage）
ProviderResponse chatStream(
    String sessionId, Profile profile, ProviderRequest request, Consumer<String> onToken);
```

约束：`chatStream` 的审计写入与 `chat` 完全同口径（每次调用一条 `llm_calls`）；模型无流式能力或流式获取失败但可整段获取时，内部降级并把整段一次性回调（R3/FR-006）。

### ReActLoop / AgentService（重载）

```java
// ReActLoop：原 run(session, msg, profile) 委托到 run(session, msg, profile, StreamListener.NOOP)
String run(Session session, String userMessage, Profile profile, StreamListener listener);
// AgentService：同模式重载
String process(Session session, String userMessage, StreamListener listener);
String processStateless(String agentName, String userMessage, StreamListener listener);
```

## 传输态实体（oryxos-web）

### StreamEvent 线协议（不落库，契约见 contracts/sse-protocol.md）

| event | data 负载（JSON） | 说明 |
|-------|------------------|------|
| `token` | `{"delta": "…"}` | 回复文本增量 |
| `tool_start` | `{"name": "shell"}` | 工具调用开始 |
| `tool_end` | `{"name": "shell", "success": true}` | 工具调用结束 |
| `done` | `{"reply": "…"} `（+ 端点各自元数据） | 恰好一个终结事件（与 error 二选一） |
| `error` | `{"code": 500, "message": "…"}` | 可读信息，不泄敏感堆栈 |
| `: ping` | —（SSE 注释行） | 心跳，非业务事件 |

### SseWriter（oryxos-web，新类）

同步 SSE 写出器：持有 `HttpServletResponse` 输出流；`event(type, payload)` 加锁写 + flush；内部虚拟线程心跳（默认 15s，`oryxos.web.sse.heartbeat-seconds` 可配）；`IOException` → disconnected 标记（后续静默丢弃，FR-008）；`close()` 停心跳。状态机：`OPEN → (DISCONNECTED)? → CLOSED`，终结事件后必 CLOSED。

## 不新增的

- 无新配置属性除 `oryxos.web.sse.heartbeat-seconds`（默认 15）。
- 会话锁、序列化、审计表结构、`AGENT.md` frontmatter 均零改动。
