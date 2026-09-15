package io.oryxos.core.metrics;

/**
 * Trace span 记录契约（039，MetricsRecorder 同款纪律）：全 default 空方法 + NOOP，core 只认接口、 OTel SDK
 * 实现在装配层（依赖倒置）。事后补记式——调用方传显式起止时间（与审计计时同源），不引入任何 上下文传播新机制；traceId 与 021 审计三表同源（审计供精确回放、OTel
 * 供跨系统链路，同源不同面）。 实现内部必须自吞异常：span 记录失败绝不影响主链路。
 */
public interface SpanRecorder {

  /** 装配缺省与单机/未配置档：零开销。 */
  SpanRecorder NOOP = new SpanRecorder() {};

  /** 一轮消息处理的根 span（AgentService 单轮 Scope 块同址，成功/失败分支各记一次）。 */
  default void recordTurnSpan(
      String traceId,
      String agentName,
      String channel,
      boolean success,
      long startEpochMs,
      long durationMs) {}

  /** 一次 LLM 调用子 span（Provider 实现既有 startedAt/durationMs 计时区间同址）。 */
  default void recordLlmSpan(
      String traceId,
      String provider,
      String model,
      boolean success,
      long startEpochMs,
      long durationMs) {}

  /** 一次工具执行子 span（ToolExecutor 审计区间同址；策略拦截也记，blockedByPolicy=true）。 */
  default void recordToolSpan(
      String traceId,
      String toolName,
      boolean success,
      boolean blockedByPolicy,
      long startEpochMs,
      long durationMs) {}
}
