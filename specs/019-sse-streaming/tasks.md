# Tasks: SSE 流式响应

**Input**: Design documents from `/specs/019-sse-streaming/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R10）、data-model.md、contracts/sse-protocol.md、quickstart.md

**Tests**: 包含测试任务——项目质量门禁要求核心逻辑有单测覆盖（宪法「开发流程与质量门禁」），且流式协议的七条语义承诺（终结唯一、拼接一致等）必须测试钉死。

**Organization**: 按用户故事分组；US1（REST 双端点打字机）为 MVP，US2（过程/异常/心跳/断开）、US3（管理台 + CLI 消费面）依次叠加。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）

## Path Conventions

Maven 多模块单体，涉及 oryxos-core / oryxos-provider / oryxos-web / oryxos-channel-cli / oryxos-boot 五个既有模块（见 plan.md「Source Code」）。

---

## Phase 1: Setup

**Purpose**: 配置属性先行

- [X] T001 新建 oryxos-web/src/main/java/io/oryxos/web/config/WebSseProperties.java（prefix `oryxos.web.sse`，仅 `heartbeat-seconds` 默认 15）并在 WebAuthConfig.java 的 `@EnableConfigurationProperties` 列表追加注册（镜像 018 WebApiKeyProperties 的注册方式）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 流式契约 + 循环打点 + Provider 流式获取——三个故事共同依赖的核心

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [X] T002 [P] 新建 oryxos-core/src/main/java/io/oryxos/core/agent/StreamListener.java：`onToken(String delta)`/`onToolStart(String toolName)`/`onToolEnd(String toolName, boolean success)` 三个同步回调 + `NOOP` 常量实例（契约与不变式见 data-model.md）
- [X] T003 [P] 在 oryxos-core/src/main/java/io/oryxos/core/provider/ProviderService.java 新增 `chatStream(String sessionId, Profile profile, ProviderRequest request, Consumer<String> onToken)` 契约（javadoc 写明：返回完整 ProviderResponse、审计与 chat 同口径、无流式能力时内部降级整段一次回调，R2/FR-006）
- [X] T004 修改 oryxos-core/src/main/java/io/oryxos/core/agent/ReActLoop.java（依赖 T002/T003）：`run` 加 listener 重载（原签名委托 `NOOP`）；LLM 调用改走 `chatStream`（listener==NOOP 时仍走 `chat`，保证非流式路径字节级不变）；每个工具执行前后回调 `onToolStart`/`onToolEnd`（ToolExecutor 零改动，R1）
- [X] T005 修改 oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java（依赖 T002/T004）：`process`/`processStateless` 加 listener 重载（原签名委托 NOOP；锁与 ProfileContext 语义不变）
- [X] T006 修改 oryxos-provider/src/main/java/io/oryxos/provider/SpringAiProviderServiceImpl.java（依赖 T003）：实现 `chatStream`——模型为 `StreamingChatModel` 时 `stream(prompt).toIterable()` 同步迭代（虚拟线程阻塞），逐 chunk 回调 content 增量（tool-call 增量不回调，R3）并聚合完整 ProviderResponse（文本/toolCalls/usage 取末尾聚合值）；非流式模型或流式异常但可整段获取时降级调 `chat` 后整段一次回调；审计复用 chat 的成功 fail-open/失败先落账逻辑（Flux 类型不出本方法，R2）
- [X] T007 修改 oryxos-provider/src/main/java/io/oryxos/provider/ProviderChatModelFactory.java：内置 mock 模型实现 `StreamingChatModel`——把既有回复文本按固定粒度切段以 `Flux.fromIterable` 返回（R7，E2E 多 token 锚点）
- [X] T008 [P] 新建 oryxos-core/src/test/java/io/oryxos/core/agent/ReActLoopStreamTest.java（依赖 T004）：stub ProviderService/ToolExecutor 断言——token 回调与工具回调的顺序反映执行顺序、tool 成对（success 真假两路）、listener==NOOP 时行为与原 run 完全等价、多轮 ReAct 连续回调
- [X] T009 [P] 新建 oryxos-provider/src/test/java/io/oryxos/provider/ProviderStreamTest.java（依赖 T006/T007）：stub StreamingChatModel 断言——分段回调与聚合文本一致、usage 聚合正确、非流式模型降级整段一次回调、流式中途异常上抛且审计落一条失败记录、成功审计与 chat 同口径、mock 模型分段流出

**Checkpoint**: core/provider 单测全绿——消费面实现可以开始

---

## Phase 3: User Story 1 - REST 调用方流式打字机 (Priority: P1) 🎯 MVP

**Goal**: 双端点 Accept 分流——带 `text/event-stream` 逐 token 推送 + done 终结；不带则字节级现状

**Independent Test**: quickstart V1/V2/V6——非流式响应与 018 交付时一致；流式收多个 token 事件且拼接 == done.reply；invoke 端点同口径

- [X] T010 [US1] 新建 oryxos-web/src/main/java/io/oryxos/web/sse/SseWriter.java：构造入 HttpServletResponse（设 `text/event-stream;charset=UTF-8` + `Cache-Control: no-cache`）；`event(String type, String payloadJson)` 加写锁同步写 + flush；`close()`；写出 IOException → disconnected 标记、后续事件静默丢弃（FR-008/R6；心跳线程 T015 补）
- [X] T011 [US1] 新建 oryxos-web/src/main/java/io/oryxos/web/sse/SseStreamListener.java：StreamListener → SseWriter 适配（onToken→`token`、onToolStart→`tool_start`、onToolEnd→`tool_end`，负载 JSON 见 contracts/sse-protocol.md §2；ObjectMapper 序列化）
- [X] T012 [US1] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/SessionApiController.java（依赖 T005/T010/T011）：`send` 按 Accept 分流——非流式路径零改动；流式分支先完成全部校验（content 校验、会话查找，失败走既有异常体系返 JSON，FR-009）再建 SseWriter，`agentService.process(session, content, listener)` 正常返回 → `done` 事件（`{"reply":…}`）→ close
- [X] T013 [US1] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/AgentApiController.java（依赖 T005/T010/T011）：`invoke` 同模式分流（校验前置：消息判空/32KB 上限/requireAgent；`processStateless(name, content, listener)`）
- [X] T014 [P] [US1] 新建 oryxos-web/src/test/java/io/oryxos/web/controller/SseStreamingTest.java（依赖 T012/T013）：MockMvc + stub AgentService——非流式响应与现状一致；流式 Content-Type 与 SSE 线格式正确；token 事件按 listener 回调序到达且拼接 == done.reply；done 恰好一个；会话不存在/消息为空时返 404/400 JSON（流未开始）；invoke 端点双路同口径

**Checkpoint**: quickstart V1/V2/V6 可走通——MVP 可交付

---

## Phase 4: User Story 2 - 过程可见与异常有终 (Priority: P2)

**Goal**: 工具事件、error 唯一终结、心跳保活、断开不丢数据

**Independent Test**: quickstart V3/V4/V5——tool 事件成对夹在流中；流中失败恰好一个 error；静默期有 `: ping`；掐断连接后会话历史与审计完整

- [X] T015 [US2] 修改 oryxos-web/src/main/java/io/oryxos/web/sse/SseWriter.java（依赖 T010/T001）：内部虚拟线程按 `WebSseProperties.heartbeatSeconds` 固定间隔写 SSE 注释行 `: ping`（与业务写共享写锁；不做空闲计时重置——固定间隔实现最简且多发心跳无害）；close/断开时线程退出（FR-007/R4）
- [X] T016 [US2] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/SessionApiController.java 与 AgentApiController.java（依赖 T012/T013）：流式分支 catch-all——`agentService` 抛异常 → `error` 事件（`{"code":…,"message":…}`，可读信息不泄堆栈，FR-003 终结唯一）→ close；断开场景（writer 已 disconnected）不再写任何事件
- [X] T017 [P] [US2] 在 oryxos-web/src/test/java/io/oryxos/web/controller/SseStreamingTest.java 追加（依赖 T014/T015/T016）：tool_start/tool_end 成对且顺序正确；流中异常 → 恰好一个 error 事件且无 done；心跳注释行按间隔出现（heartbeat-seconds 调小验证）；模拟写出 IOException → 后续事件静默丢弃、process 正常完成不上抛
- [X] T018 [US2] 新建 oryxos-boot/src/test/java/io/oryxos/boot/SseStreamingE2ETest.java（依赖 T007、T012~T016；镜像 ApiKeyAuthE2ETest 的 SpringBootTest+mock provider 模式）：真实 HTTP 流式——token 事件数 >1、首个 token 事件早于 done 到达、拼接 == done.reply（mock 分段，SC-002 弱化版自动化锚点；30% 比例量化由 T025 真机走查用真实 provider 记录）；掐断连接后会话历史含完整回复、llm_calls 照写（V5/SC-004）；同一消息流式与非流式的审计条数口径一致（V10/SC-007）；018 门禁复验（apikey 开启时无 Key 流式请求 401 JSON，V7/SC-005）

**Checkpoint**: quickstart V3/V4/V5/V7/V10 可走通——US1+US2 独立可测

---

## Phase 5: User Story 3 - 自有消费面打字机（管理台 + CLI） (Priority: P3)

**Goal**: 管理台聊天页与 `oryxos chat` 终端打字机体验

**Independent Test**: quickstart V8/V9——终端逐段打印 + 工具状态提示；浏览器聊天页逐段渲染、错误可恢复、刷新后历史一致

- [X] T019 [US3] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/AgentApiController.java（依赖 T016，与 T013/T016 同文件串行）：`consoleSend`（`/agents/{name}/session/messages`）同模式 Accept 分流（管理台聊天页实际端点，R5/contracts §1）
- [X] T020 [US3] 修改 oryxos-web/src/main/frontend/src/App.vue（依赖 T019）：聊天发送改 fetch + `Accept: text/event-stream` + ReadableStream 手工解析 SSE 行协议（R9）——token 增量渲染打字机、tool_start/tool_end 显示/清除状态提示、error 呈现可读提示且输入框恢复、done 后以 reply 替换缓冲；非流式响应（异常兜底）保持旧逻辑可用
- [X] T021 [US3] 修改 oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/CliChannel.java（依赖 T005）：构造进程内 StreamListener——onToken `System.out.print(delta)` + flush、onToolStart/End 单行状态提示、回复结束补换行；改走 `agentService.process(session, msg, listener)`（FR-015/R8；provider 降级时自动整段输出，无特判）
- [X] T022 [P] [US3] 新建 oryxos-channel-cli/src/test/java/io/oryxos/channel/cli/CliChannelStreamTest.java（依赖 T021）：stub AgentService 触发 listener 回调，捕获 stdout 断言——token 逐段打印顺序、工具提示行出现、最终输出含完整回复（如既有 CLI 模块无测试基建则并入 T008 的 core 断言并在本任务注明）

**Checkpoint**: 全部故事独立可测

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 配置样例、文档与全量验收

- [X] T023 [P] 在 config/application.yml.example 追加 `oryxos.web.sse.heartbeat-seconds` 配置段与注释（默认 15，SSE 心跳间隔）
- [X] T024 [P] 文档同步：website/docs/api.md 与 website/zh/docs/api.md 补 SSE 流式协议节（对齐 contracts/sse-protocol.md 的端点/事件/语义承诺）；docs/CliGuide.md 的 chat 节补打字机行为说明
- [X] T025 按 quickstart.md 完整走查 V1~V10（V9 浏览器走查复用 018 的缓存 Chromium + playwright-core 方式）并记录到 specs/019-sse-streaming/acceptance-report.md（SC-001~SC-008 逐项对勾，镜像 018 报告形式）
- [X] T026 运行 `mvn verify` 全量质量门禁并清零新增告警（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（T001）**: 无依赖
- **Phase 2（T002~T009）**: T002∥T003 先行；T004 依赖 T002/T003；T005 依赖 T004；T006 依赖 T003；T007 独立；T008 依赖 T004；T009 依赖 T006/T007
- **Phase 3（US1）**: 依赖 Phase 2；T010/T011 可并行；T012/T013 依赖 T005/T010/T011；T014 依赖 T012/T013
- **Phase 4（US2）**: T015 依赖 T010/T001；T016 依赖 T012/T013；T017 依赖 T014~T016；T018 依赖 T007 与 T012~T016
- **Phase 5（US3）**: T019 依赖 T016（同文件串行链见下）；T020 依赖 T019；T021 依赖 T005；T022 依赖 T021
- **Phase 6**: 依赖全部故事完成

### 同文件递进链（禁止并行）

- `SseWriter.java`: T010 → T015
- `SessionApiController.java`: T012 → T016
- `AgentApiController.java`: T013 → T016 → T019
- `SseStreamingTest.java`: T014 → T017

### Parallel Opportunities

- Phase 2：T002 ∥ T003 ∥ T007；T008 ∥ T009（不同模块测试）
- Phase 3：T010 ∥ T011；T012 ∥ T013（不同 controller）
- Phase 5：T021/T022（CLI）与 T019/T020（web）两条线并行
- Phase 6：T023 ∥ T024

---

## Parallel Example: Phase 2

```bash
# 契约与 mock 增强三线并行：
Task: "T002 StreamListener 契约"
Task: "T003 ProviderService.chatStream 契约"
Task: "T007 mock 模型分段流式"
# 然后 T004 → T005 / T006 → （并行）T008 ∥ T009
```

---

## Implementation Strategy

### MVP First（US1 Only）

1. Phase 1 + Phase 2（T001~T009）：契约、循环打点、provider 流式全就位
2. Phase 3（T010~T014）：REST 双端点打字机
3. **STOP and VALIDATE**: quickstart V1/V2/V6 走通即可演示（curl -N 看打字机）
4. US2/US3 依次叠加，各自 checkpoint 独立验收

### 注意

- 非流式路径回归是红线：T004/T012/T013 均要求「listener==NOOP / 无 Accept 时行为字节级不变」，T014 的回归用例先行
- 终结唯一（恰好一个 done 或 error）是协议承诺的核心，T016/T017 必须把双终结与无终结两类违约都测死
- 每完成一个 Phase 提交一次（scope 按主要触点：core/provider/web/cli）
