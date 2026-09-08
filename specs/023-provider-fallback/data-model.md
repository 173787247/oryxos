# Data Model: Provider 失败切换与业务指标导出

**Feature**: 023-provider-fallback | **Date**: 2026-09-02

**零 schema 变更**——每次尝试一条 llm_calls（既有表既有列自然承载）；指标是内存计数序列不落库。

## fallback 声明（frontmatter，纯增量）

```yaml
provider:
  name: deepseek          # 主 Provider（现状字段不变，未知仍启动报错）
  model: deepseek-chat
  temperature: 0.7
  fallback:               # 新增：有序备用列表，可空=现状零变化
    - name: qwen
      model: qwen-plus
    - name: kimi
      model: moonshot-v1-8k
```

### Profile.ProviderRef（core，纯增量组件）

```java
record ProviderRef(String name, String model, Double temperature, List<FallbackRef> fallbacks) {
  ProviderRef { fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks); }
  ProviderRef(String name, String model, Double temperature) { this(name, model, temperature, List.of()); } // 旧构造兼容
  record FallbackRef(String name, String model) {}
}
```

校验口径：候选缺 name/model → 加载抛校验异常（与主同）；候选引用未注册 Provider → 加载 WARN 不阻断（tools 同口径），运行时跳过。

## 尝试序列（provider 实现内部，不出契约）

```text
attempts = [ (provider.name, provider.model) ] + provider.fallbacks
for attempt in attempts:
    registry.find(attempt.name)  → 缺失: WARN 跳过（不落审计），下一个（FR-008）
    调用（buildPrompt 用 attempt.model；审计 provider/model 用 attempt 值）
    成功 → recordSuccess + 指标 → 返回
    失败 → recordFailure + 指标（每次尝试各一条，FR-005）
         → FallbackClassifier.isSwitchable(e) 且有下一候选
             → WARN「provider 切换: from → to」（MDC 带 traceId）+ 切换计数 → 下一个
             → 否则原样上抛（最后错误，现状口径）
流式附加条件：text/toolCalls 累计非空（首内容已出）→ 一律不切，直接落账上抛（FR-007）
```

## 可切换性分类表（FallbackClassifier）

| 判定输入 | 结果 |
|----------|------|
| 异常链含网络/超时类（连接失败、IO、Timeout） | 切换 |
| HTTP 5xx / 429 / 401 / 403 / 408 | 切换 |
| HTTP 400 / 404 / 422 等其余 4xx | 不切换 |
| 提取不到状态码的其他运行时异常 | 切换（宁多试一次备用） |
| ProviderNotFoundException（候选未注册） | 跳过候选（非失败尝试，不落审计） |

## MetricsRecorder 契约（core/metrics/，NOOP 默认）

| 方法 | 埋点位置 | 对应指标 |
|------|----------|----------|
| `recordLlmCall(provider, model, success, durationMs)` | Provider 实现审计调用旁 | `oryxos_llm_calls_total` + `oryxos_llm_call_duration_seconds` |
| `recordLlmTokens(provider, model, prompt, completion)` | 成功审计旁 | `oryxos_llm_tokens_total` |
| `recordToolInvocation(tool, success)` | ToolExecutor 审计旁 | `oryxos_tool_invocations_total` |
| `recordPolicyBlock(tool)` | ToolExecutor 策略拒绝点 | `oryxos_policy_blocks_total` |
| `recordFallbackSwitch(from, to)` | 切换点 | `oryxos_fallback_switches_total` |

- 实现纪律：所有埋点 try/catch 吞异常（FR-010）；`MetricsRecorder.NOOP` 为旧构造/无 MeterRegistry 场景兜底（StreamListener.NOOP 惯例第三例）
- 装配：`ObjectProvider<MeterRegistry>` 有 → `MicrometerMetricsRecorder`（cli）；无（如 `oryxos chat`）→ NOOP

## 指标目录

见 [research.md R6](research.md)；全部 `oryxos_` 前缀，标签基数有界（注册集内的 provider/model/tool 名）。
