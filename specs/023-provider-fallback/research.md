# Research: Provider 失败切换与业务指标导出

**Feature**: 023-provider-fallback | **Date**: 2026-09-02

## R1 — fallback 声明形态：ProviderRef 纯增量组件

**Decision**: `Profile.ProviderRef` 增加第 4 组件 `List<FallbackRef> fallbacks`（`FallbackRef(String name, String model)`），旧 3 参构造委托空列表（021 MessageResponse/AgentExecution 同款兼容惯例）。YAML：

```yaml
provider:
  name: deepseek
  model: deepseek-chat
  fallback:
    - name: qwen
      model: qwen-plus
```

`ProfileLoader.toProviderRef` 解析 `fallback` 列表；候选缺 name/model 抛校验异常（与主 provider 同口径）；候选引用未注册 provider **WARN 但不阻断加载**——与 `tools` 引用未注册能力的既有口径一致（摸底：AgentLoader javadoc「WARN 但仍登记」），运行时再跳过（FR-008）。主 provider 未知仍启动抛异常（现状不变）。

**Rationale**: fallback 候选的可用性是运行时属性（Provider 可随时增删），加载期硬校验会让"删一个备用 Provider"连坐一批 Agent 启动失败——治理面反而变脆。

**Alternatives considered**: 顶层 `fallbacks:` 节（与 provider 分离——语义归属错位，备用就是 provider 配置的一部分）；字符串简写 `fallback: [qwen]`（缺 model 语义，同 Provider 不同模型是常见场景）。

## R2 — 切换收口点：SpringAiProviderServiceImpl 内部，契约零改动

**Decision**: `ProviderService` 接口与 `ReActLoop` 调用点零改动。实现内把「主 + fallbacks」展开为尝试序列 `List<Attempt(name, model)>`，chat/chatStream 逐个执行：成功即返回；失败先按现状 recordFailure 落账，`FallbackClassifier` 判定可切换且有下一候选 → WARN 日志（from→to，MDC 已带 traceId）+ 切换计数 + 下一个；否则原样上抛。**attempt 的 name/model 必须贯穿三处**：`registry.find(name)`、`buildPrompt` 的 `options.model(...)`、审计 record 的 provider/model 参数——摸底确认现状三处都取 `profile.provider()`，需抽出按 attempt 参数化的内部方法。

**Rationale**: 021 trace（环境读取）、022 加密（注册表收口）同一手法第三次运用：横切关切在实现层收口，上游契约即承诺。每次尝试沿用既有 recordSuccess/recordFailure → 「每尝试一条审计」免费达成（FR-005），trace 同链天然成立（尝试都在同一处理线程，TraceContext 就位）。

**Alternatives considered**: ReActLoop 层重试（污染循环语义，且 max_iterations 口径会被搅浑）；装饰器 FallbackProviderService 包一层（契约层可行，但 buildPrompt/审计的 model 参数化仍要进实现——包装层拿不到内部结构，反而两处改）。

## R3 — 可切换性分类：按 HTTP 状态码 + 异常链

**Decision**: `FallbackClassifier.isSwitchable(RuntimeException)`（provider 模块内静态工具类）：

| 形态 | 判定 | 理由 |
|------|------|------|
| 异常链含 `ResourceAccessException`/`IOException`/`TimeoutException` | 切 | 网络/超时——换端点最典型收益 |
| 状态码 5xx | 切 | 服务端故障 |
| 429 | 切 | 限流——换 Provider 即换配额 |
| 401/403 | 切 | 本 Provider 凭证问题，换 Provider 有独立凭证（spec 明示认证失败可切） |
| 408 | 切 | 请求超时 |
| 400/404/422 等其余 4xx | 不切 | 请求本身非法/资源不存在——换 Provider 无成功预期（FR-003） |
| 提取不到状态码的其他 RuntimeException | 切 | 宁可多试一次备用（可用性优先），全败仍上抛最后错误 |
| `ProviderNotFoundException` | 跳过该候选（FR-008），不计入"失败尝试"审计 | 配置引用问题非调用失败 |

状态码提取：遍历异常链找 Spring `HttpStatusCodeException`/WebClient `WebClientResponseException` 或 Spring AI 异常的状态承载形态（实现时按实际类路径穷举，测试钉死分类表）。

**Rationale**: 分类原则来自 spec Assumptions——「换一个 Provider 有合理成功预期的才算可切换」；未知异常偏向切换是可用性与诚实的平衡：多一次尝试的代价是一次调用超时，漏切的代价是本可避免的服务中断。

**Alternatives considered**: Spring AI 的 Transient/NonTransient 二分（401/403 被归 NonTransient，与"换 Provider 换凭证"语义冲突）；Spring Retry 框架（引依赖 + 退避语义违背 SC-007 无额外等待）。

## R4 — 流式切换边界：复用既有累计判定

**Decision**: chatStream 的尝试循环里，失败时以 `text.isEmpty() && toolCalls.isEmpty()`（既有 StringBuilder/聚合器累计）判定「首内容片段未出」：未出 → 可按 R3 切换（onToken 从未被回调，客户端无感知）；已出 → 不切换，按既有失败路径落账上抛（019 error 事件语义收尾）。心跳注释由 SseWriter 层发出、不经 onToken，天然不算内容（spec Assumption 自动成立）。

**Rationale**: 这正是既有「无流式能力降级」的判定条件（摸底 L144），语义同源零新概念；token 已出后重试必然重复输出——诚实优于聪明（spec 原话）。

**Alternatives considered**: 缓冲首 N token 再转发以扩大可切换窗口（引入人为延迟，违背流式的意义；YAGNI）。

## R5 — 指标依赖倒置：契约进 core，Micrometer 实现在 cli

**Decision**: `MetricsRecorder` 接口进 `core/metrics/`（方法：`recordLlmCall(provider, model, success, durationMs)`、`recordLlmTokens(provider, model, promptTokens, completionTokens)`、`recordToolInvocation(tool, success)`、`recordPolicyBlock(tool)`、`recordFallbackSwitch(from, to)`；`NOOP` 常量默认）。`MicrometerMetricsRecorder` 实现放 oryxos-cli，cli pom 加 `micrometer-core` **编译**依赖——运行时 jar 已由 boot 的 actuator 传递带入，**无新增运行时构件**（OWASP 无新扫描面）。装配：`OryxOsRuntime` 里 `ObjectProvider<MeterRegistry>` 有则 Micrometer 实现、无则 NOOP（`oryxos chat` 等无 actuator 上下文安全兜底）。埋点：LLM 两类在 `SpringAiProviderServiceImpl` 审计调用旁；工具/策略在 `ToolExecutor` 审计调用旁；切换在切换点。所有埋点 try/catch 吞异常（FR-010 指标失败不伤主链路）。

**Rationale**: core/provider 不能依赖 micrometer（模块纪律）；接口 + NOOP 是 StreamListener.NOOP/ToolPolicyService.ALLOW_ALL 的既有零破坏锚点惯例第三例。埋点贴着审计调用放，保证「指标计数 ≈ 审计条数」可对照（SC-005/SC-006 的验证基础）。

**Alternatives considered**: 直接在各模块用 Micrometer（依赖扩散到 core/provider/tool，模块纪律破坏）；从审计表定时聚合导出（引入拉取延迟与双份口径漂移，指标本该是内存计数）。

## R6 — 指标目录（oryxos_ 前缀，Prometheus 惯例）

| 指标 | 类型 | 标签 | 语义 |
|------|------|------|------|
| `oryxos_llm_calls_total` | counter | provider, model, outcome(success/failure) | 每次尝试计一次（与 llm_calls 行数同口径） |
| `oryxos_llm_call_duration_seconds` | timer | provider, model | 单次尝试耗时分布 |
| `oryxos_llm_tokens_total` | counter | provider, model, type(prompt/completion) | token 消耗 |
| `oryxos_tool_invocations_total` | counter | tool, outcome | 工具调用（含被拦截的失败） |
| `oryxos_policy_blocks_total` | counter | tool | 020 策略拦截 |
| `oryxos_fallback_switches_total` | counter | from, to | 切换次数（告警核心信号） |

标签基数有界（provider/model/tool 均为有限注册集）；Micrometer timer 自带 count/sum/max，无需单独平均值指标。

## R7 — 不做的（边界收口）

- **智能路由**：roadmap 明示留到数据积累后；本刀的指标+审计恰好是那份数据的来源
- **断路器/半开状态机、退避等待**：YAGNI + SC-007 明令无额外等待；按序同步重试够用
- **跨请求 Provider 健康记忆**：无状态原则（宪法「无状态实例」）；每次调用独立从主开始
- **管理台新页面**：016 报表覆盖"人看"；Prometheus 面向机器与告警
- **Spring Retry / Resilience4j**：引依赖换不来更简单的三十行循环
