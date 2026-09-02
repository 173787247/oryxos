# Tasks: Provider 失败切换与业务指标导出

**Input**: Design documents from `/specs/023-provider-fallback/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R7）、data-model.md、contracts/fallback-metrics.md、quickstart.md

**Tests**: 包含测试任务——质量门禁要求核心逻辑单测覆盖（宪法「开发流程与质量门禁」），且切换分类表（SC-004）、审计每尝试一条（SC-003）、回归零破坏（SC-002）、指标口径对照（SC-005/SC-006）必须测试钉死。

**Organization**: 按用户故事分组；声明解析与指标契约在 Foundational 一次成型——US1（切换不中断）为 MVP，US2（留痕回放）、US3（监控指标）依次叠加。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）

## Path Conventions

Maven 多模块单体，涉及 oryxos-core / oryxos-provider / oryxos-cli / oryxos-boot 四个既有模块（见 plan.md「Source Code」）。零新表、零新模块、零运行时新构件。

---

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: fallback 声明解析 + 指标契约——三个故事共同依赖的地基

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [X] T001 [P] 修改 oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java：ProviderRef 加第 4 组件 `List<FallbackRef> fallbacks`（紧凑构造 List.copyOf，null→List.of()）+ 旧 3 参构造委托空列表（021 兼容惯例）；新增嵌套 `record FallbackRef(String name, String model)`
- [X] T002 修改 oryxos-core/src/main/java/io/oryxos/core/profile/ProfileLoader.java（依赖 T001）：toProviderRef 解析 `fallback` 列表——每项 name/model 缺失抛 ProfileValidationException（与主 provider 同口径）；候选引用未注册 provider 记 WARN **不阻断加载**（tools 既有口径，R1），仍保留进列表（运行时再跳过）
- [X] T003 [P] 新建 oryxos-core/src/main/java/io/oryxos/core/metrics/MetricsRecorder.java（契约见 data-model.md）：五方法（recordLlmCall/recordLlmTokens/recordToolInvocation/recordPolicyBlock/recordFallbackSwitch）全部 default 空实现 + `MetricsRecorder NOOP` 常量（StreamListener.NOOP 惯例第三例）；javadoc 注明实现纪律「埋点异常不得影响主链路」
- [X] T004 [P] 修改 oryxos-core/src/test/java/io/oryxos/core/profile/ProfileLoaderTest.java（依赖 T002）：追加——fallback 两候选按序解析、候选缺 model 抛校验异常、候选引用未知 provider WARN 但加载成功、无 fallback 字段时 fallbacks 为空列表（旧 YAML 零变化）

**Checkpoint**: 声明解析单测全绿——切换实现可以开始

---

## Phase 2: User Story 1 - Provider 故障时服务不中断 (Priority: P1) 🎯 MVP

**Goal**: 单次调用故障按序切换备用重发；候选耗尽上抛最后错误；业务性失败不切；流式首片段边界；零声明零变化

**Independent Test**: quickstart V1/V3/V4/V5——主败备成对话正常；全败上抛；400 不切；流式透明切换

- [X] T005 [P] [US1] 新建 oryxos-provider/src/main/java/io/oryxos/provider/FallbackClassifier.java（分类表见 data-model.md）：静态 `boolean isSwitchable(RuntimeException)`——遍历异常链提取 HTTP 状态码（Spring HttpStatusCodeException/WebClientResponseException/Spring AI 异常的状态承载形态，按实际类路径穷举）与网络/超时类（ResourceAccessException/IOException/TimeoutException cause）；5xx/429/401/403/408/网络/无状态码 → true，400/404/422 等其余 4xx → false
- [X] T006 [P] [US1] 新建单测 oryxos-provider/src/test/java/io/oryxos/provider/FallbackClassifierTest.java（依赖 T005）：分类表逐行钉死——各状态码正反例、网络异常链（cause 深埋）、无状态码 RuntimeException → true、嵌套包装异常
- [X] T007 [US1] 修改 oryxos-provider/src/main/java/io/oryxos/provider/SpringAiProviderServiceImpl.java（依赖 T005，**本特性核心**）：
  - 抽尝试序列：`List<Attempt(name, model)>` = 主 + `profile.provider().fallbacks()`；chat/chatStream 外层循环逐个执行
  - **attempt 参数化贯穿三处**（R2 陷阱）：`registry.find(attempt.name)`、`buildPrompt` 的 `options.model(attempt.model)`、recordSuccess/recordFailure 的 provider/model 参数——备用调用必须用备用模型名且审计如实
  - 失败处理：recordFailure 照旧（每尝试一条）→ `FallbackClassifier.isSwitchable` 且有下一候选 → WARN「provider 切换: from → to」（sanitize，MDC 自带 traceId）→ 下一个；否则原样上抛
  - 候选 `registry.find` 缺失 → WARN 跳过不落审计（FR-008）；ProviderNotFoundException 对**主** provider 保持现状直抛（零声明回归）
  - chatStream 附加：失败时 `text.isEmpty() && toolCalls.isEmpty()` 才允许切换（首片段边界，R4）；已有输出按既有路径落账上抛；UnsupportedOperationException 降级逻辑保持在"单次尝试"内不变
- [X] T008 [P] [US1] 新建单测 oryxos-provider/src/test/java/io/oryxos/provider/ProviderFallbackTest.java（依赖 T007；mock ChatModel builder + registry + auditor，镜像既有 provider 测试形态）：主败备成返回备结果且备用调用携带备 model、全部候选失败上抛最后异常、不可切换异常（400）直接上抛无第二次尝试、候选未注册跳过直达第三候选、零 fallback 声明行为与现状一致（单次调用单条审计）、流式首片段前失败切换成功 / 已出 token 后失败不切换、**同一 service 实例连续两次 chat：第一次主败备成后第二次仍先尝试主 Provider**（FR-004 无健康记忆断言，verify 调用顺序）
- [X] T009 [US1] 在 oryxos-provider/src/test/java/io/oryxos/provider/ProviderFallbackTest.java 追加审计断言（依赖 T008，同文件串行）：主败备成场景 auditor.record 恰被调两次——第一次 provider=主/success=false、第二次 provider=备/model=备 model/success=true（SC-003 的单测面；verify 参数捕获）

**Checkpoint**: quickstart V1/V3/V4/V5 语义可单测复现——MVP 可交付

---

## Phase 3: User Story 2 - 切换过程可回放可追责 (Priority: P2)

**Goal**: 主备尝试审计各一条、trace 同链、切换 WARN 带 traceId——E2E 全链钉死

**Independent Test**: quickstart V1/V2——真实 HTTP 主败备成后审计两条、时间线同链、日志可 grep

- [X] T010 [US2] 新建 oryxos-boot/src/test/java/io/oryxos/boot/ProviderFallbackE2ETest.java（依赖 T007；镜像 TraceE2ETest 模式：临时工作区 + mock provider + 真实 HTTP + SQLite）：**broken provider 必须启动前预置**（Agent 启动加载时 knownProviders 校验要能看到它，运行时 API 注册有时序坑）——首选 `properties` 里 `oryxos.providers[1].name=broken` + base-url 指向 `http://127.0.0.1:1/v1` 走 YAML 种子路径；若 ProvidersProperties 无 base-url 键则回退「@DynamicPropertySource 前直插 providers 表 / 或 seedWorkspace 阶段写入」；Agent 主 broken + fallback mock——invoke 返回 200 且回复为 mock 文案（SC-001）；llm_calls 中 broken 失败与 mock 成功记录成对出现且同 traceId；`GET /audit/trace/{id}` 时间线主备 LLM 步同链按时间序（SC-003）；ListAppender 断言 WARN「provider 切换」日志存在且 MDC traceId 与本轮一致
- [X] T011 [US2] 在 oryxos-boot/src/test/java/io/oryxos/boot/ProviderFallbackE2ETest.java 追加（依赖 T010，同文件串行）：零声明 Agent（只配 mock）行为回归——单轮审计条数与 022 前基线一致（SC-002/SC-006 锚点）；全败 Agent（broken + fallback broken2）invoke 报错且无成功行

**Checkpoint**: quickstart V1/V2/V3 可走通——US1+US2 独立可测

---

## Phase 4: User Story 3 - 监控栈里可见调用健康度 (Priority: P3)

**Goal**: /actuator/prometheus 上五类 oryxos_* 业务指标，与实际行为计数一致、与审计正交

**Independent Test**: quickstart V6——触发调用/拦截/切换后抓端点，指标在位且计数对照一致

- [X] T012 [US3] 修改 oryxos-cli/pom.xml：加 `io.micrometer:micrometer-core` 编译依赖（运行时 jar 已由 boot actuator 传递带入，注释注明零新增运行时构件）
- [X] T013 [US3] 新建 oryxos-cli/src/main/java/io/oryxos/cli/MicrometerMetricsRecorder.java（依赖 T003/T012）：实现五方法落 oryxos_* 指标（目录见 contracts §3）——Counter/Timer 经 MeterRegistry 惰性获取；全部方法 try/catch 吞异常记 DEBUG（FR-010 埋点不伤主链路）；标签值 null 兜底为 "unknown"。配套新建 oryxos-cli/src/test/java/io/oryxos/cli/MicrometerMetricsRecorderTest.java：SimpleMeterRegistry 断言五类指标名/标签/计数，**并注入恒抛异常的 MeterRegistry 断言五方法全部静默不抛**（FR-010 显式断言）
- [X] T014 [US3] 修改 oryxos-provider/src/main/java/io/oryxos/provider/SpringAiProviderServiceImpl.java（依赖 T007/T003，同文件串行）：构造注入 `MetricsRecorder`（旧构造委托 NOOP 保既有测试）；recordSuccess/recordFailure 旁挂 recordLlmCall（每尝试）、成功侧挂 recordLlmTokens、切换点挂 recordFallbackSwitch
- [X] T015 [P] [US3] 修改 oryxos-core/src/main/java/io/oryxos/core/agent/ToolExecutor.java（依赖 T003）：构造注入 `MetricsRecorder`（旧构造委托 NOOP）；审计调用旁挂 recordToolInvocation（成败如实）；策略拒绝点挂 recordPolicyBlock
- [X] T016 [US3] 修改 oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java（依赖 T013~T015）：`@Bean MetricsRecorder`——`ObjectProvider<MeterRegistry>` 有则 MicrometerMetricsRecorder、无则 NOOP（chat 命令等无 actuator 上下文兜底）；providerService 与 toolExecutor 装配点注入
- [X] T017 [US3] 在 oryxos-boot/src/test/java/io/oryxos/boot/ProviderFallbackE2ETest.java 追加（依赖 T011/T016，同文件串行）：`GET /actuator/prometheus` 抓文本断言——`oryxos_llm_calls_total`（broken/failure 与 mock/success 序列在位且计数与 llm_calls 表行数对照一致，SC-005/SC-006）、`oryxos_fallback_switches_total{from="broken",to="mock"}` ≥1、`oryxos_tool_invocations_total` 与 `oryxos_llm_tokens_total` 在位；配 GLOBAL_DENY 触发拦截 → `oryxos_policy_blocks_total` 递增

**Checkpoint**: 全部故事独立可测

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 文档与全量验收

- [X] T018 [P] 文档同步：CLAUDE.md AGENT.md 示例 provider 节补 `fallback` 字段与一句口径；website/zh/docs/api.md 与 website/docs/api.md 补「Provider 失败切换」与「业务指标」小节（对齐 contracts）；docs/CliGuide.md 提及 fallback 声明与 /actuator/prometheus 接入
- [ ] T019 按 quickstart.md 完整走查 V1~V6（真机 fat JAR：broken provider 指不通端口 + mock 备用；V4 用 python 400 stub）并记录到 specs/023-provider-fallback/acceptance-report.md（SC-001~SC-007 逐项对勾，镜像 018~022 报告形式）
- [ ] T020 运行 `mvn verify` 全量质量门禁并清零新增告警（CRLF 日志参数一律 sanitize——022 教训前置）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（T001~T004）**: T001 ∥ T003 先行；T002 依赖 T001；T004 依赖 T002
- **Phase 2（US1）**: T005 ∥（T001~T004 后）；T006 随 T005；T007 依赖 T005；T008→T009
- **Phase 3（US2）**: T010 依赖 T007；T011 依赖 T010
- **Phase 4（US3）**: T012→T013；T014 依赖 T007（同文件串行）；T015 ∥ T013；T016 依赖 T013~T015；T017 依赖 T011/T016
- **Phase 5**: 依赖全部故事完成

### 同文件递进链（禁止并行）

- `SpringAiProviderServiceImpl.java`: T007 → T014
- `ProviderFallbackTest.java`: T008 → T009
- `ProviderFallbackE2ETest.java`: T010 → T011 → T017
- `OryxOsRuntime.java`: T016 单点

### Parallel Opportunities

- Phase 1：T001 ∥ T003；T004 收尾
- Phase 2：T005/T006 与 Phase 1 尾部并行推进；T008 内场景独立
- Phase 4：T013 ∥ T015（不同模块）
- Phase 5：T018 与 T019 前半并行

---

## Parallel Example: Phase 1

```bash
Task: "T001 ProviderRef.fallbacks 组件"   ∥   Task: "T003 MetricsRecorder 契约"
# 然后：T002 ProfileLoader 解析 → T004 解析单测
```

---

## Implementation Strategy

### MVP First（US1 Only）

1. Phase 1（T001~T004）：声明与契约就位
2. Phase 2（T005~T009）：切换循环 + 分类器 + 单测闭环
3. **STOP and VALIDATE**: 单测层主败备成/全败/边界全绿即可演示
4. US2（E2E 留痕）/US3（指标）依次叠加，各自 checkpoint 独立验收

### 注意

- **attempt 参数化是本刀最大陷阱**（R2）：registry 查找/buildPrompt model/审计参数三处必须全部按 attempt 贯穿——漏一处就是"拿主模型名调备 Provider"或"审计失真"
- **契约零改动红线**：ProviderService/ReActLoop/审计接口不动；MetricsRecorder 与 ToolExecutor/ProviderServiceImpl 的旧构造委托 NOOP 保全既有测试（019~022 教训制度化）
- **流中已出内容绝不切换**（FR-007）：判定条件与既有降级判定同源（text/toolCalls 累计），不另造状态
- **指标埋点吞异常**：任何 MeterRegistry 故障不得影响调用主链路（FR-010）
- 每完成一个 Phase 提交一次（scope 按主要触点：core/provider/cli）
