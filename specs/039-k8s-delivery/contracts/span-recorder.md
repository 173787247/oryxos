# Contract: SpanRecorder（039 新增，MetricsRecorder 同款纪律）

**位置**: `oryxos-core/src/main/java/io/oryxos/core/metrics/SpanRecorder.java`（契约在 core，实现 `oryxos-cli/OtelSpanRecorder`，依赖倒置）

**总纪律**: 全 default 空方法 + `SpanRecorder NOOP = new SpanRecorder() {}`；实现内部自吞异常（span 记录失败绝不影响主链路）；事后补记式——调用方传显式起止时间，不引入上下文传播新机制；与审计同 traceId（021），审计供精确回放、OTel 供跨系统链路，同源不同面。

```java
public interface SpanRecorder {

  SpanRecorder NOOP = new SpanRecorder() {};

  /** 一轮消息处理的根 span（AgentService 单轮 Scope 块同址，成功/失败分支各记一次）。 */
  default void recordTurnSpan(
      String traceId, String agentName, String channel,
      boolean success, long startEpochMs, long durationMs) {}

  /** 一次 LLM 调用子 span（SpringAiProviderServiceImpl 既有 startedAt/durationMs 区间同址）。 */
  default void recordLlmSpan(
      String traceId, String provider, String model,
      boolean success, long startEpochMs, long durationMs) {}

  /** 一次工具执行子 span（ToolExecutor auditor.record 区间同址；策略拦截也记，blocked=true）。 */
  default void recordToolSpan(
      String traceId, String toolName,
      boolean success, boolean blockedByPolicy, long startEpochMs, long durationMs) {}
}
```

## OTel 映射（OtelSpanRecorder 实现义务）

- **traceId 映射**: 021 UUID 去横线 = 32 hex，直接作 OTel TraceId；非法/空 traceId 静默丢弃该 span（不抛）。
- **确定性父子**: turn 根 span 的 SpanId = traceId 前 16 hex；LLM/工具子 span 以之为 parentSpanId、自身 SpanId 随机——三个记录点彼此无需传句柄，乱序补记也能拼出正确链路。
- **时间戳**: 以调用方传入的 startEpochMs/durationMs 显式设置 span 起止（SDK 原生支持），与审计表耗时同源。
- **导出**: BatchSpanProcessor + OTLP gRPC exporter；endpoint 不可达静默降级（SDK 内建重试/丢弃）+ 低频 WARN。
- **属性**: turn→`oryxos.agent`,`oryxos.channel`；llm→`oryxos.provider`,`oryxos.model`；tool→`oryxos.tool`,`oryxos.blocked_by_policy`；失败 span 置 StatusCode.ERROR。

## 装配契约（OryxOsRuntime）

- `oryxos.otel.endpoint` 为空（默认）→ 注入 `SpanRecorder.NOOP`：零依赖初始化、零连接、零导出尝试（FR-008 由单测钉住）。
- 非空 → `OtelSpanRecorder(endpoint, samplerRatio)`；应用关闭时 flush+shutdown SDK（ContextClosedEvent 或 destroyMethod）。

## 单测义务

1. 三调用点（fake SpanRecorder）：成功/失败/策略拦截路径各记一次、traceId 取自 TraceContext、时间戳与审计计时同源
2. OtelSpanRecorder（InMemorySpanExporter）：traceId 映射与父子链路正确、显式时间戳生效、非法 traceId 不炸
3. NOOP 档：endpoint 未配时装配为 NOOP、全量既有测试零变化
