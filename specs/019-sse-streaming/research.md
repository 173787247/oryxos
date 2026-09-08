# Research: SSE 流式响应

**Feature**: 019-sse-streaming | **Date**: 2026-08-27

技术上下文无 NEEDS CLARIFICATION；本文记录关键技术裁决，全部基于对现有代码的实地摸底（`ReActLoop`、`AgentService`、`SpringAiProviderServiceImpl`、`SessionApiController`/`AgentApiController`、`CliChannel`、管理台 `App.vue` 聊天请求）。

## R1. 流式能力的核心抽象：同步回调 listener（依赖倒置）

**Decision**: 在 oryxos-core 新增 `StreamListener` 接口（`onToken(String delta)`、`onToolStart(String toolName)`、`onToolEnd(String toolName, boolean success)`，全部同步回调，提供 `NOOP` 默认实例）。`ReActLoop.run`、`AgentService.process`/`processStateless` 各加带 listener 的重载，原签名委托到 `listener = NOOP` 的新路径——单一实现，零重复。三个消费面（Web SSE 写出器、CLI 终端打印器、未来渠道）都是这个接口的实现。

**Rationale**: 宪法 I 要求循环行为可定制——listener 正是定制点；宪法 VII 禁的是异步编程模型，同步回调在调用线程上执行，不引入任何异步语义。契约放 core、实现在下游，符合项目依赖倒置惯例。工具事件在 `ReActLoop` 调 `ToolExecutor` 前后打点，`ToolExecutor` 自身零改动。

**Alternatives considered**: 事件队列 + 消费线程（否——引入队列生命周期与背压问题，同步回调天然背压）；返回 Iterator/Stream 由调用方拉取（否——把循环控制权外翻，违背宪法 I 的循环自持）。

## R2. Provider 流式：Reactor 类型封死在 provider 模块内部

**Decision**: `ProviderService`（core 契约）新增 `chatStream(sessionId, profile, request, Consumer<String> onToken)`，返回完整 `ProviderResponse`（文本 + toolCalls + usage）。`SpringAiProviderServiceImpl` 实现：模型实现了 Spring AI `StreamingChatModel` 时走 `model.stream(prompt)`，用 `Flux.toIterable()` **同步迭代**（虚拟线程阻塞等待每个 chunk），逐 chunk 回调 `onToken` 并聚合完整响应；`Flux`/Reactor 类型只出现在该实现方法内部，不进方法签名、不出模块。审计复用 `chat` 的同一套 record 逻辑（成功 fail-open / 失败先落账再上抛，宪法 V 口径不变）。

**Rationale**: Spring AI 的流式运输天然是 Flux（reactor-core 已是其传递依赖），拒绝它等于自写 SSE HTTP 解析——重复造轮子且脱离「协议转换归 Spring AI」的宪法 II 分工。`toIterable()` 把响应式流降为同步迭代，核心循环与 core 契约看不到任何 Reactor 类型，宪法 VII 的「不引入异步编程模型」在架构边界上成立（与「SQLite 驱动内部有线程」同一性质：库的实现细节，不是我们的编程模型）。

**Alternatives considered**: 自实现 OpenAI SSE 客户端（否——重复造轮子，多 provider 差异重新自己扛）；`CompletableFuture` 桥接（否——宪法 VII 明令禁止）。

## R3. token 事件与工具轮的边界

**Decision**: 流式时只把 content 增量回调为 token（tool-call 增量不进 token 事件）；主流 provider 在工具调用轮 content 为空，故正常情形下「token 拼接 == done 完整回复」严格成立（FR-004）。个别 provider 在工具轮携带正文时，该正文照发 token（无法撤回已推送内容），done.reply 仍取 ReActLoop 的最终回复。自动化验收用增强后的 mock provider（可控分段）断言严格相等。

**Rationale**: 流式 chunk 到达在先、本轮是否有 toolCalls 判定在后，「中间轮不发 token」在物理上不可行；按业界通行做法转发 content 增量，把严格断言锚定在可控的 mock 场景。

## R4. Web 写出：同步 Servlet 直写，不用 SseEmitter

**Decision**: 不用 Spring MVC 的 `SseEmitter`/`ResponseBodyEmitter`（依赖 async servlet 机制）。Controller 流式分支注入 `HttpServletResponse`，设 `Content-Type: text/event-stream`（禁缓存、UTF-8）后在**当前虚拟线程上同步阻塞写** `OutputStream` 并逐事件 flush。封装 `SseWriter`（oryxos-web）：`event(type, payloadJson)` 同步写 + flush（内部加写锁）、`close()`；心跳为 writer 内部起的一条虚拟线程，空闲期每隔配置间隔（默认 15s，`oryxos.web.sse.heartbeat-seconds`）写 SSE 注释行 `: ping`，与业务写共用同一把锁。

**Rationale**: roadmap 原话「虚拟线程内逐 token 写出，不引入 WebFlux」——同步直写正是它的字面实现；async servlet 与 SseEmitter 是为平台线程稀缺时代设计的，虚拟线程下阻塞写毫无代价。心跳线程是虚拟线程并发（宪法 VII 明确允许），不是异步编程模型。

**Alternatives considered**: `SseEmitter`（否——引入 async dispatch 生命周期与超时管理，且与「同步阻塞」的架构叙事相悖）；WebFlux（宪法 VII 明令禁止）。

## R5. 分流与端点覆盖

**Decision**: 按请求头 `Accept` 包含 `text/event-stream` 分流（`@RequestMapping` 不拆映射，方法内判定，保证非流式路径字节级不变）。覆盖三个端点：`POST /sessions/{id}/messages`、`POST /agents/{name}/invoke`（spec FR-001 双端点），以及 `POST /agents/{name}/session/messages`（管理台聊天页实际调用的端点——摸底确认 `App.vue:1482`，FR-013 的隐含依赖，契约文档一并声明）。

**Rationale**: 三端点最终都进 `AgentService`，流式分支只是「换一个 listener + 换一种响应写法」，共用 `SseWriter` 与事件协议；漏掉 console 端点则 US3 无法成立。

## R6. 失败语义与断开处理

**Decision**: 流开始前可判定的失败（会话/Agent 不存在 404、认证 401、非法请求 400）走现有异常体系（`@RestControllerAdvice` JSON）——流式分支在写响应头**之前**完成全部校验。流开始后异常 → `SseWriter` 写 `error` 事件后 `close`（response 已 committed，状态码不可改，FR-009/Edge Case 口径）。写出 `IOException`（客户端断开）→ writer 置 disconnected 标记，后续事件静默丢弃，`AgentService` 照常跑完并落库（FR-008，Clarifications 断开语义）；心跳线程同时退出。

## R7. mock provider 支持流式

**Decision**: 内置 mock ChatModel 增加 `StreamingChatModel` 实现：把既有回复文本切成多段以 `Flux.fromIterable` 返回。配置零改动（仍是 `- name: mock`）。

**Rationale**: E2E 与真机走查需要无 key 可复现的**多 token 流式**场景（SC-002/SC-003 的自动化锚点）；不支持流式的 provider 降级路径则由单测 stub 覆盖（FR-006 两个方向都有测试）。

## R8. CLI 打字机

**Decision**: `CliChannel` 构造进程内 `StreamListener`：`onToken` → `System.out.print(delta)` + flush；`onToolStart/End` → 单行状态提示（如 `[调用工具 shell …]` / `[工具 shell 完成]`）；结束补换行。走 `AgentService.process(session, msg, listener)` 进程内直连，不经 HTTP（FR-015）。Provider 无流式能力时 `chatStream` 降级为整段一次回调，终端表现为一次性输出——FR-015 的回落语义自动满足，无需 CLI 侧特判。

## R9. 管理台打字机

**Decision**: `App.vue` 聊天发送改为 `fetch` + `ReadableStream` 手工解析 SSE 行协议（`EventSource` 不支持 POST，不引前端依赖），带 `Accept: text/event-stream` 头；token 事件增量渲染、tool 事件显示状态条、error 事件呈现提示并恢复输入、done 后以最终回复替换缓冲。浏览器兼容：现代浏览器 fetch streaming 全支持（管理台无旧浏览器包袱）。

## R10. 契约与文档

**Decision**: 事件协议（类型、负载字段、终结语义、心跳注释行、断开语义、降级语义）固化在 `contracts/sse-protocol.md`，并同步 website 的 `api.md`（中英）与 OpenAPI 描述备注（springdoc 对 SSE 的表达以文字说明为主）。原则五：接口即承诺。
