# Implementation Plan: Provider 失败切换与业务指标导出

**Branch**: `023-provider-fallback` | **Date**: 2026-09-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/023-provider-fallback/spec.md`

## Summary

fallback 收口在 `SpringAiProviderServiceImpl`（**ProviderService 契约零改动**，ReActLoop 无感知——021/022 同纪律）：chat/chatStream 内部把「主 + 有序 fallback」展开成尝试序列逐个执行，`FallbackClassifier` 按异常形态判可切换性（网络/超时/5xx/429/401/403 切，400 类业务错不切）；每次尝试沿用既有 recordSuccess/recordFailure 落审计（provider/model 按 attempt 如实），021 trace 天然同链；流式以「首内容片段未出」为切换边界（复用既有 text/toolCalls 累计判定）。声明面：`Profile.ProviderRef` 加 `fallbacks` 组件（旧构造委托空列表），`ProfileLoader` 解析 `provider.fallback` 列表（未知候选 WARN 不阻断，与 tools 同口径）。指标面：`MetricsRecorder` 契约进 core（NOOP 默认），Micrometer 实现放 cli（`micrometer-core` 编译依赖——运行时 jar 已由 boot actuator 带入，无新增运行时构件），埋点挂在既有审计调用旁（LLM/工具/策略/切换五类，`oryxos_` 前缀）。零新表、零新模块、零运行时新构件。

## Technical Context

**Language/Version**: Java 21（同步循环重试，虚拟线程内阻塞——宪法 VII）

**Primary Dependencies**: `micrometer-core` 加入 oryxos-cli 编译依赖（运行时已在 boot 类路径，OWASP 无新扫描面）；其余零新增

**Storage**: 零 schema 变更——每次尝试一条 llm_calls（既有口径自然承载主备留痕）

**Testing**: JUnit 5——provider（FallbackClassifier 分类表 / chat 主败备成序列 / 流式边界 / 候选跳过）、core（ProfileLoader fallback 解析）、cli（MetricsRecorder 装配）、boot E2E（mock 双 provider 主败备成 + trace 同链 + /actuator/prometheus 指标对照）；`mvn verify` 全量门禁

**Target Platform**: Linux server（单 fat JAR）

**Project Type**: Maven 多模块单体——core（ProviderRef.fallbacks + ProfileLoader + MetricsRecorder 契约）、provider（切换循环 + 分类器 + LLM 指标点）、cli（Micrometer 实现 + 装配 + pom 依赖）、boot（E2E）

**Performance Goals**: 无 fallback 声明时零额外开销（序列长度 1，无分支成本）；SC-007 无退避等待，最坏时延 = 候选数 × 单次超时

**Constraints**: ProviderService 契约零改动；审计口径零变化（FR-010/SC-006）；流中已出内容绝不切换（FR-007）；每次调用独立从主开始（无健康记忆）；指标采集异常不影响主链路

**Scale/Scope**: 约 4 个新文件 + 6 个既有文件小改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | ReActLoop 零改动；fallback 是单次调用内部行为，循环无感知 | ✅ |
| II Spring AI 边界 | 仍只用协议转换；切换循环是自实现逻辑，不用 Spring AI/Spring Retry 的重试机制 | ✅ |
| III Provider 显式映射 | 强化：fallback 候选同样按 name 走注册表显式查找（`registry.find`），无类型扫描 | ✅ |
| IV 目录=Agent / Skill | frontmatter 纯增量字段 `provider.fallback`；未声明零变化 | ✅ |
| V 审计 Day One | 强化：每次尝试各一条 llm_calls（失败主也留痕）；指标与审计正交、不改口径 | ✅ |
| VI 安全是地基 | 候选凭证走既有注册表（022 加密）；无新凭证面 | ✅ |
| VII 同步 + 虚拟线程 | 按序同步重试，无异步/退避框架；Reactor 仍不出 chatStream 方法体 | ✅ |
| VIII 状态外置 / 手工 schema | 零 schema 变更；无跨请求健康状态（每次调用独立） | ✅ |
| 模块约束 | MetricsRecorder 契约进 core（provider/core 调用点消费）；Micrometer 实现在 cli 装配层；无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/023-provider-fallback/
├── plan.md              # 本文件
├── research.md          # Phase 0：7 项技术裁决（R1~R7）
├── data-model.md        # Phase 1：fallback 声明 + 尝试序列 + 指标目录
├── quickstart.md        # Phase 1：V1~V6 验收走查
├── contracts/
│   └── fallback-metrics.md  # Phase 1：切换语义承诺 + 审计口径 + 指标目录
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/
│   ├── profile/Profile.java          # 修改：ProviderRef 加 fallbacks 组件（旧构造委托空列表）
│   ├── profile/ProfileLoader.java    # 修改：解析 provider.fallback 列表（未知候选 WARN 不阻断）
│   └── metrics/MetricsRecorder.java  # 新增：业务指标契约（NOOP 常量默认）——LLM/工具/策略/切换五类
└── src/test/java/io/oryxos/core/profile/ProfileLoaderTest.java  # 修改：追加 fallback 解析用例

oryxos-provider/
├── src/main/java/io/oryxos/provider/
│   ├── SpringAiProviderServiceImpl.java  # 修改：chat/chatStream 尝试序列循环 + 切换 WARN + 指标点
│   └── FallbackClassifier.java           # 新增：异常 → 可切换性分类（状态码/异常链判定）
└── src/test/java/io/oryxos/provider/
    ├── FallbackClassifierTest.java       # 新增：分类表（5xx/429/401/超时切；400/404 不切）
    └── ProviderFallbackTest.java         # 新增：主败备成/全败上抛/候选跳过/流式边界/审计每尝试一条

oryxos-cli/
├── pom.xml                               # 修改：加 micrometer-core 编译依赖
└── src/main/java/io/oryxos/cli/
    ├── MicrometerMetricsRecorder.java    # 新增：MeterRegistry 落地 oryxos_* 指标
    └── OryxOsRuntime.java                # 修改：装配（有 MeterRegistry 用 Micrometer，否则 NOOP）+ 注入调用点

oryxos-core/（工具/策略指标点）
└── src/main/java/io/oryxos/core/agent/ToolExecutor.java  # 修改：审计旁挂工具/策略拦截计数

oryxos-boot/
└── src/test/java/io/oryxos/boot/ProviderFallbackE2ETest.java  # 新增：双 mock 主败备成 + trace 同链 + prometheus 指标对照
```

**Structure Decision**: 切换收口在 ProviderService 实现内部——上游（ReActLoop/AgentService/controllers）零改动，是 021「trace 环境读取」、022「注册表收口加密」同一手法的第三次运用：横切关切在实现层收口、契约即承诺不动。指标契约在 core（调用点在 core/provider），实现在装配层（依赖倒置，chat 命令等无 MeterRegistry 场景 NOOP 兜底）。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | fallback 声明进 ProviderRef | `provider.fallback` 有序列表（name+model）；未知候选加载 WARN、运行时跳过（与 tools 同口径） |
| R2 | 切换收口在 Provider 实现 | 契约零改动；attempt(name, model) 贯穿 buildPrompt/审计——model 按 attempt 如实 |
| R3 | FallbackClassifier 分类 | 状态码提取：5xx/429/401/403/408/网络异常 → 切；400/404/422 → 不切；提取不到 → 切（宁多试） |
| R4 | 流式边界复用累计判定 | `text.isEmpty() && toolCalls.isEmpty()` = 首片段未出可切换（既有降级判定同源） |
| R5 | MetricsRecorder 依赖倒置 | 契约 core、Micrometer 实现 cli（micrometer-core 编译依赖，运行时构件零新增）；NOOP 兜底 |
| R6 | 指标目录 oryxos_* | llm_calls_total/llm_call_duration/llm_tokens_total/tool_invocations_total/policy_blocks_total/fallback_switches_total |
| R7 | 不做的 | 智能路由、断路器、退避等待、跨请求健康记忆、管理台新页面 |
