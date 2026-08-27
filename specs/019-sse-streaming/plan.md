# Implementation Plan: SSE 流式响应

**Branch**: `019-sse-streaming` | **Date**: 2026-08-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/019-sse-streaming/spec.md`

## Summary

为三个消息端点与 CLI 加流式输出，收掉 v0.2 最后一角。技术路线：core 新增 `StreamListener` 同步回调契约（依赖倒置，Web SSE / CLI 打印是它的两个消费面），`ReActLoop`/`AgentService` 加 listener 重载（原路径走 NOOP，单一代码路径）；`ProviderService` 新增 `chatStream`，实现里用 Spring AI `StreamingChatModel` + `Flux.toIterable()` 同步迭代——Reactor 类型封死在 provider 模块内部；Web 侧不用 SseEmitter，controller 在虚拟线程上同步直写 `HttpServletResponse` 输出流（`SseWriter` 封装事件写出、心跳虚拟线程、断开处理）；mock provider 增加分段流式能力供无 key 验收。零新依赖（reactor-core 本就是 Spring AI 传递依赖）、零新表、零新模块。

## Technical Context

**Language/Version**: Java 21（virtual thread——流式写出与心跳的并发基座）

**Primary Dependencies**: Spring Boot 3.x（Spring MVC 同步 servlet）、Spring AI（`StreamingChatModel.stream` → Flux，仅 provider 模块内部消化）；前端 Vue 3（fetch + ReadableStream 解析 SSE）。零新增依赖

**Storage**: 无新表、无变更（流事件纯传输态；审计与会话落库口径不变）

**Testing**: JUnit 5——core（listener 打点顺序）、provider（流式聚合/降级/审计口径，stub StreamingChatModel）、web（MockMvc 断言 SSE 线格式与分流）、boot E2E（mock 流式全链路 + 断开数据一致性）；`mvn verify` 全量门禁

**Target Platform**: Linux server（单 fat JAR，同现状）

**Project Type**: Maven 多模块单体——涉及 oryxos-core（契约+循环打点）、oryxos-provider（流式实现+mock 增强）、oryxos-web（SSE 写出+三端点分流+前端）、oryxos-channel-cli（打字机）四个既有模块

**Performance Goals**: 首 token 到达时间 ≤ 完整生成时间的 30%（SC-002）；逐事件 flush，无攒批

**Constraints**: 宪法 VII——同步阻塞 + 虚拟线程，不引入 WebFlux/Reactor 编程模型进核心循环（Flux 只在 provider 实现内部、经 toIterable 降为同步迭代）；非流式路径字节级不变（SC-001）；终结事件恰好一个（FR-003）

**Scale/Scope**: 约 6 个新文件 + 8 个既有文件改动；范围=REST 三端点（含管理台 console 端点）+ CLI + 管理台前端

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | 循环仍自持：只在既有节点（LLM 调用、工具执行前后）加同步回调打点，调度逻辑零变化；listener 是宪法 I 预留的「定制循环行为」空间 | ✅ |
| II Spring AI 边界 | 流式仍只是协议转换：`chatModel.stream()` 与 `chatModel.call()` 同性质；工具执行仍由 ToolExecutor 独占，无自动 tool 执行 | ✅ |
| III Provider 显式映射 | 不涉及（复用既有 registry 查找） | ✅ |
| IV 目录=Agent / Skill | 不涉及 | ✅ |
| V 审计 Day One | `chatStream` 复用 `chat` 的同一套审计逻辑（成功 fail-open/失败先落账）；断开后照常落库；SC-007 钉死一致性 | ✅ |
| VI 安全是地基 | 018 门禁天然覆盖（filter 拦 `/api/v1/*`，流开始前校验）；error 事件不泄敏感堆栈；无新凭证面 | ✅ |
| VII 同步 + 虚拟线程 | 核心裁决点：不用 SseEmitter/WebFlux，虚拟线程同步直写；Flux 经 `toIterable()` 降为同步迭代且不出 provider 模块（research R2/R4 详证）；心跳是虚拟线程并发非异步模型 | ✅ |
| VIII 状态外置 / 手工 schema | 无新状态、无表变更；SseWriter 是请求生命周期内的传输对象 | ✅ |
| 模块约束 | 不新建模块；契约进 core、实现在 provider/web/cli 各归其位，无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/019-sse-streaming/
├── plan.md              # 本文件
├── research.md          # Phase 0：10 项技术裁决（R1~R10）
├── data-model.md        # Phase 1：StreamListener 契约 + 线协议 + SseWriter 状态机（无新表）
├── quickstart.md        # Phase 1：V1~V10 验收走查
├── contracts/
│   └── sse-protocol.md  # Phase 1：SSE 事件协议（对外承诺）
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/
│   ├── agent/StreamListener.java        # 新增：流式回调契约（含 NOOP）
│   ├── agent/ReActLoop.java             # 修改：run 加 listener 重载，LLM/工具节点打点
│   ├── agent/AgentService.java          # 修改：process/processStateless 加 listener 重载
│   └── provider/ProviderService.java    # 修改：新增 chatStream 契约
└── src/test/java/io/oryxos/core/agent/
    └── ReActLoopStreamTest.java         # 新增：打点顺序/工具事件成对/NOOP 等价

oryxos-provider/
├── src/main/java/io/oryxos/provider/
│   ├── SpringAiProviderServiceImpl.java # 修改：chatStream 实现（stream→toIterable 同步聚合、降级、审计复用）
│   └── ProviderChatModelFactory.java    # 修改：mock 模型实现 StreamingChatModel（分段流出）
└── src/test/java/io/oryxos/provider/
    └── ProviderStreamTest.java          # 新增：流式聚合/降级/审计口径/工具轮 content

oryxos-web/
├── src/main/java/io/oryxos/web/
│   ├── sse/SseWriter.java               # 新增：同步 SSE 写出器（事件/心跳/断开/close）
│   ├── sse/SseStreamListener.java       # 新增：StreamListener → SseWriter 适配
│   ├── config/WebSseProperties.java     # 新增：heartbeat-seconds（默认 15）
│   ├── controller/SessionApiController.java  # 修改：messages 端点 Accept 分流
│   └── controller/AgentApiController.java    # 修改：invoke + session/messages 分流
├── src/main/frontend/src/App.vue        # 修改：聊天页 fetch+ReadableStream 打字机
└── src/test/java/io/oryxos/web/
    └── controller/SseStreamingTest.java # 新增：线格式/分流/终结唯一/前置失败 JSON

oryxos-channel-cli/
└── src/main/java/io/oryxos/channel/cli/CliChannel.java  # 修改：打字机 listener

oryxos-boot/
└── src/test/java/io/oryxos/boot/
    └── SseStreamingE2ETest.java         # 新增：mock 流式全链路 + 断开数据一致性 + 审计对照
```

**Structure Decision**: 契约（`StreamListener`、`ProviderService.chatStream`）进 oryxos-core，三个消费面各归其模块（web 的 SSE 写出、cli 的终端打印、provider 的流式获取）——与项目「跨模块契约放 core、下游实现」的依赖倒置惯例完全同构。不新建模块：SSE 写出是 web 的响应形态，不是独立能力域。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | StreamListener 同步回调契约 | core 定义、NOOP 默认、原路径委托——单一代码路径；ToolExecutor 零改动 |
| R2 | Reactor 封死在 provider 内部 | `stream().toIterable()` 同步迭代；Flux 不进签名不出模块；审计复用 chat 口径 |
| R3 | token 只转发 content 增量 | 工具轮 content 为空的常规情形下拼接严格相等；mock 场景锚定自动化断言 |
| R4 | 同步直写不用 SseEmitter | 虚拟线程阻塞写 + 逐事件 flush；心跳=writer 内部虚拟线程 + 共享写锁 |
| R5 | 三端点分流 | sessions/messages、agents/invoke、agents/session/messages（管理台聊天实际端点，摸底 App.vue:1482） |
| R6 | 失败分层 | 流前 JSON 状态码（校验前置于写头）；流中 error 事件；断开→静默丢弃+照常落库 |
| R7 | mock 支持流式 | 分段 Flux，无 key 可验多 token（E2E/真机锚点）；降级路径由 stub 单测覆盖 |
| R8 | CLI 打字机 | 进程内 listener 直打 stdout；降级自动回落整段，无特判 |
| R9 | 管理台 fetch+ReadableStream | EventSource 不支持 POST；零前端新依赖 |
| R10 | 契约固化 | contracts/sse-protocol.md + website api.md 同步（原则五） |
