# Tasks: 审计 Trace 串联与脱敏

**Input**: Design documents from `/specs/021-audit-trace/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R8）、data-model.md、contracts/trace-api.md、quickstart.md

**Tests**: 包含测试任务——项目质量门禁要求核心逻辑有单测覆盖（宪法「开发流程与质量门禁」），且串联完整性（SC-002）、并发隔离（SC-003）、脱敏不误伤（SC-006）必须测试钉死。

**Organization**: 按用户故事分组；TraceContext + 落列在 Foundational 一次成型，各故事承载查询/回传/展示消费面——US1（全链路回放）为 MVP，US2（回传三通道 + 日志）、US3（管理台时间线 + 脱敏）依次叠加。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）

## Path Conventions

Maven 多模块单体，涉及 oryxos-core / oryxos-storage / oryxos-web / oryxos-cli / oryxos-boot 五个既有模块（见 plan.md「Source Code」）。

---

## Phase 1: Setup

**Purpose**: 存储地基先行

- [X] T001 修改 oryxos-storage/src/main/java/io/oryxos/storage/AuditSchemaUpgrade.java：幂等 ALTER 为 `llm_calls`/`tool_invocations`/`agent_executions` 各加 `trace_id VARCHAR(64)` 可空列 + 建三个 `idx_*_trace` 索引（沿用 PRAGMA 检查模式）；oryxos-storage/src/main/resources/schema.sql 三个建表段同步加列与索引（016/020 双轨模式）；oryxos-storage/src/test/java/io/oryxos/storage/AuditSchemaUpgradeTest.java 追加三列断言

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: TraceContext + 审计落列——三个故事共同依赖的核心

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [X] T002 [P] 新建 oryxos-core/src/main/java/io/oryxos/core/agent/TraceContext.java（契约见 data-model.md）：`Scope openIfAbsent()`（无则生成 UUID、置 ThreadLocal + `MDC.put("traceId")`，返回含 traceId 与 owner 标记的 AutoCloseable Scope；已有则复用 owner=false）、`String current()`、`Scope.close()` 仅 owner 清 ThreadLocal+MDC（镜像 ProfileContext「入口 set、finally 必清」纪律，javadoc 注明虚拟线程复用防串号）
- [X] T003 [P] 新建单测 oryxos-core/src/test/java/io/oryxos/core/agent/TraceContextTest.java（依赖 T002）：open 生成 UUID 且 MDC 同步、嵌套 openIfAbsent 复用不覆盖、内层 close 不清外层、owner close 后 ThreadLocal 与 MDC 均空、多线程各自独立
- [X] T004 修改 oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java（依赖 T002）：`process`（锁内主体）与 `processStateless` 入口 `try (var scope = TraceContext.openIfAbsent())` 包裹全程（已开启则沿用——REST/SSE controller 先开的场景）；既有 ProfileContext/锁语义零变化
- [X] T005 [P] 修改 oryxos-storage 审计实现（依赖 T001/T002）：LlmCall.java 与 ToolInvocation.java 加 `traceId` 字段（`@Column(name = "trace_id")`）；JpaLlmCallAuditor.java 与 JpaToolInvocationAuditor.java 落库时 `record.setTraceId(TraceContext.current())`（**Auditor 接口零改动**，R2 红线）；LlmCallRepository.java 与 ToolInvocationRepository.java 加 `findByTraceId(String)`
- [X] T006 [P] 在既有测试中钉零改动红线（依赖 T005）：oryxos-storage/src/test/java/io/oryxos/storage/LlmCallRepositoryTest.java 或新增用例断言——TraceContext 开启时落库记录带 traceId、未开启时为 null 且写入照常（旧行为等价）

**Checkpoint**: TraceContext 单测全绿 + 落列可查——消费面实现可以开始

---

## Phase 3: User Story 1 - 凭 trace ID 回放单轮全链路 (Priority: P1) 🎯 MVP

**Goal**: 按 trace ID 查询合并时间线（LLM+工具按时间序、每步明细、成本/token/耗时汇总）；旧数据零影响

**Independent Test**: quickstart V1/V2——触发多轮调工具的处理 → 按 traceId 查时间线步数/顺序/汇总正确；两轮互不混串；旧数据既有查询照常

- [X] T007 [US1] 修改 oryxos-web/src/main/java/io/oryxos/web/audit/AuditMetricsService.java（依赖 T005）：新增 `traceTimeline(String traceId)`——两 repo `findByTraceId` 合并、按 createdAt 排序为 steps（type/name/success/durationMs/at；LLM 步 tokens/costMicros；TOOL 步 inputSummary/resultSummary/errorMessage/blockedBy——摘要先截断 200 字符，脱敏 T018 接入前暂原样）+ summary（steps/llmCalls/toolCalls/totalTokens/costMicros 合计/totalDurationMs=末步 at−首步 at+末步耗时）；未命中返回 found=false 空 steps
- [X] T008 [US1] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/AuditApiController.java（依赖 T007）：`GET /api/v1/audit/trace/{traceId}` 返回 TraceTimelineView（契约见 trace-api.md §3；未命中 200 + found=false 不报错）；新建 oryxos-web/src/main/java/io/oryxos/web/controller/dto/TraceTimelineView.java（record，List 组件 copyOf 保不可变——020 SpotBugs 教训）
- [X] T009 [P] [US1] 修改列表视图加 trace 维度（依赖 T005）：oryxos-web/src/main/java/io/oryxos/web/controller/dto/LlmCallView.java 与 ToolInvocationView.java 各加 `traceId` 字段（from 方法同步）
- [X] T010 [P] [US1] 新建 oryxos-web/src/test/java/io/oryxos/web/controller/TraceTimelineTest.java（依赖 T007/T008）：mock 双 repo——合并排序正确（LLM/TOOL 交错时间序）、LLM 步含 tokens/cost、TOOL 步含摘要与 blockedBy、summary 各项合计正确、found=false 空时间线、列表视图 traceId 字段在位、**traceId 为 null 的旧行在列表视图正常返回**（FR-010 旧数据兼容断言）
- [X] T011 [US1] 新建 oryxos-boot/src/test/java/io/oryxos/boot/TraceE2ETest.java（依赖 T004/T005/T008；镜像 ToolPolicyE2ETest 的 mock provider 模式）：invoke 一轮（mock 两 LLM+一工具）→ 从审计表取该轮 traceId → `GET /audit/trace/{id}` 步数=3、时间序正确、summary 合计正确（SC-002）；连续两轮各自 traceId 不同且互不混串；并发两 Agent 同时 invoke 审计无串号（SC-003，虚拟线程并发）

**Checkpoint**: quickstart V1/V2 可走通——MVP 可交付

---

## Phase 4: User Story 2 - trace ID 回传三通道 + 日志贯穿 (Priority: P2)

**Goal**: REST 响应/SSE 事件/执行历史都能拿到 trace ID；日志 MDC 同值贯穿可互查

**Independent Test**: quickstart V1/V3/V4/V5——三通道取到的 ID 查审计必命中；grep 日志命中本轮关键行

- [X] T012 [US2] REST 回传（依赖 T002）：oryxos-web/src/main/java/io/oryxos/web/controller/dto/MessageResponse.java 加 `traceId` 组件（旧单参构造保留委托 null 保兼容）；SessionApiController.send 与 AgentApiController.invoke/consoleSend 非流式路径——调用前 `TraceContext.openIfAbsent()` 拿 ID、finally close、响应带出（AgentService 检测已开启则沿用）
- [X] T013 [US2] SSE 回传（依赖 T012）：oryxos-web/src/main/java/io/oryxos/web/sse/SseStreamSupport.java——stream() 内建 writer 后先发 `event: trace`（`{"traceId":…}`，controller 已 open 的 ID）、`done` 负载加 `traceId` 字段（019 协议只增不改，旧客户端忽略未知事件）；oryxos-web/src/test/java/io/oryxos/web/controller/SseStreamingTest.java 追加首业务事件为 trace、done 带 traceId 的断言，并**更新既有事件序断言以容纳流首 trace 事件**（`containsExactly` 类断言需前插 trace——测试适配属预期改动，不是协议破坏）
- [X] T014 [US2] 执行历史回传（依赖 T001/T002）：oryxos-storage/src/main/java/io/oryxos/storage/AgentExecutionEntity.java 加 `traceId` 字段；触发链路（AgentExecutionService/triggerAsync 所在类，按实际文件）主线程生成 trace ID → 落执行记录 → **显式传入后台虚拟线程置入 TraceContext**（R4 唯一跨线程点）；oryxos-web/src/main/java/io/oryxos/web/controller/dto/AgentExecutionView.java 加 `traceId` 字段
- [X] T015 [P] [US2] 在 oryxos-boot/src/test/java/io/oryxos/boot/TraceE2ETest.java 追加（依赖 T011~T014，同文件串行）：REST 非流式响应 traceId 与审计一致；SSE 流 trace 事件 ID 与 done/审计一致；trigger 后执行记录 traceId 与该轮审计一致（后台线程传递正确）；MDC 日志验证——用 ListAppender 或日志文件断言本轮关键日志行携带同一 traceId（SC-004/SC-007）

**Checkpoint**: quickstart V3/V4/V5 可走通——US1+US2 独立可测

---

## Phase 5: User Story 3 - 管理台时间线 + 脱敏 (Priority: P3)

**Goal**: 报表页 trace 查询与时间线视图；展示层脱敏（落库原文）

**Independent Test**: quickstart V7/V8——含敏感形态的参数在 API/管理台掩码、库中原文；报表页按 ID 查到时间线、明细行可点查

- [X] T016 [US3] 新建 oryxos-web/src/main/java/io/oryxos/web/audit/Redactor.java：静态 `String redact(String)`——四类内置形态（API key 已知前缀长随机串 / `Authorization` 凭证段 / URL userinfo / `password|passwd|secret|token|api_key|apikey|access_key` 字段值）命中值替换为 前4字符+`****`；未命中原样返回；规则内置不可配置（data-model 脱敏表）
- [X] T017 [P] [US3] 新建 oryxos-web/src/test/java/io/oryxos/web/audit/RedactorTest.java（依赖 T016）：四类形态各正例掩码、普通 JSON/中文文本/普通 URL 不误伤、混合文本只掩敏感段、null/空安全
- [X] T018 [US3] 修改 oryxos-web/src/main/java/io/oryxos/web/audit/AuditMetricsService.java（依赖 T007/T016，同文件串行）：traceTimeline 的 inputSummary/resultSummary/errorMessage 经 `Redactor.redact` 后返回（截断→脱敏顺序）；oryxos-web/src/test/java/io/oryxos/web/controller/TraceTimelineTest.java 追加：含敏感参数的步骤展示为掩码、库侧（mock repo 原值）不受影响
- [X] T019 [US3] 修改 oryxos-web/src/main/frontend/src/App.vue（依赖 T008/T009）：报表页顶部加 trace ID 查询框 + 时间线渲染（步骤序/类型徽标 LLM|TOOL/名称/成败/耗时；LLM 步 token 与成本、TOOL 步脱敏摘要与 blockedBy 标记；found=false 显示「未找到」）；LLM/工具明细表行显示 traceId 并可点击填入查询框；执行历史行显示 traceId
- [X] T020 [P] [US3] 在 oryxos-boot/src/test/java/io/oryxos/boot/TraceE2ETest.java 追加（依赖 T015/T018，同文件串行）：发送含 `"password":"p@ss123"` 与 `Bearer sk-…` 形态的消息（mock 把消息原文当 save_memory 参数）→ 时间线 API 返回掩码、`tool_invocations.input_json` 库中原文完整（SC-006 双向断言）

**Checkpoint**: 全部故事独立可测

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 文档与全量验收

- [X] T021 [P] 文档同步：website/zh/docs/api.md 与 website/docs/api.md 补 trace 节（回传通道/时间线端点/脱敏承诺，对齐 contracts/trace-api.md）；docs/CliGuide.md 或部署文档提及「响应里的 traceId 用于报障定位」
- [X] T022 [P] 更新 CLAUDE.md 审计相关段落：补 trace_id 列与「一次消息处理=一个 trace」口径一句话（SQLite 核心表小节）
- [ ] T023 按 quickstart.md 完整走查 V1~V8（V8 浏览器走查复用缓存 Chromium + playwright-core 方式）并记录到 specs/021-audit-trace/acceptance-report.md（SC-001~SC-008 逐项对勾，镜像 018~020 报告形式）
- [ ] T024 运行 `mvn verify` 全量质量门禁并清零新增告警（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（T001）**: 无依赖
- **Phase 2（T002~T006）**: T002∥T001 先行；T003 依赖 T002；T004 依赖 T002；T005 依赖 T001/T002；T006 依赖 T005
- **Phase 3（US1）**: 依赖 Phase 2；T007→T008；T009∥T010 可与 T008 并行推进；T011 依赖 T004/T005/T008
- **Phase 4（US2）**: T012 依赖 T002；T013 依赖 T012；T014 依赖 T001/T002；T015 依赖 T011~T014
- **Phase 5（US3）**: T016∥T017 先行；T018 依赖 T007/T016；T019 依赖 T008/T009；T020 依赖 T015/T018
- **Phase 6**: 依赖全部故事完成

### 同文件递进链（禁止并行）

- `AuditMetricsService.java`: T007 → T018
- `TraceE2ETest.java`: T011 → T015 → T020
- `SseStreamingTest.java`: 019 既有 → T013 追加
- `AuditSchemaUpgradeTest.java`: 既有 → T001 追加

### Parallel Opportunities

- Phase 2：T002 ∥ T001；T003 ∥ T004 ∥ T005（T005 内三组文件亦可分头）
- Phase 3：T009 ∥ T010（与 T008 不同文件）
- Phase 5：T016/T017（脱敏）与 T019（前端）两条线并行
- Phase 6：T021 ∥ T022

---

## Parallel Example: Phase 2

```bash
Task: "T001 三列 ALTER + 索引"   ∥   Task: "T002 TraceContext"
# 然后：
Task: "T003 TraceContext 单测"  ∥  Task: "T004 AgentService 收口"  ∥  Task: "T005 审计落列"
```

---

## Implementation Strategy

### MVP First（US1 Only）

1. Phase 1 + Phase 2（T001~T006）：列、上下文、落库全就位
2. Phase 3（T007~T011）：时间线查询闭环
3. **STOP and VALIDATE**: quickstart V1/V2 走通即可演示（invoke → 从审计拿 ID → 时间线回放）
4. US2/US3 依次叠加，各自 checkpoint 独立验收

### 注意

- **审计契约零改动是红线**（R2）：Auditor 接口不动，Jpa 实现自读 TraceContext——T006 显式断言未开启上下文时行为与现状等价
- **MDC 必须 finally 清理**：TraceContext.Scope 是 AutoCloseable，全部 open 点用 try-with-resources/finally；T003 钉死 owner 语义
- **triggerAsync 是唯一跨线程点**（R4）：主线程生成→落记录→显式传入后台线程；T015 专项断言
- 每完成一个 Phase 提交一次（scope 按主要触点：core/storage/web）
