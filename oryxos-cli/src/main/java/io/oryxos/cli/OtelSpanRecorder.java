package io.oryxos.cli;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.oryxos.core.metrics.SpanRecorder;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTel 实现（039，装配层）：事后补记式 span——显式起止时间与审计计时同源；021 traceId（UUID）去横线即 OTel TraceId；turn 根 span 的
 * SpanId 取 traceId 前 16 hex（确定性），LLM/工具子 span 以之为远程父—— 三个记录点彼此无需传句柄，乱序补记也能拼出正确链路。BatchSpanProcessor
 * 异步批量导出，端点不可达由 SDK 缓冲丢弃（宪法 VII：不进请求路径）。一切异常自吞（span 失败绝不影响主链路）。
 */
public final class OtelSpanRecorder implements SpanRecorder, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OtelSpanRecorder.class);

  private static final AttributeKey<String> ATTR_AGENT = AttributeKey.stringKey("oryxos.agent");
  private static final AttributeKey<String> ATTR_CHANNEL = AttributeKey.stringKey("oryxos.channel");
  private static final AttributeKey<String> ATTR_PROVIDER =
      AttributeKey.stringKey("oryxos.provider");
  private static final AttributeKey<String> ATTR_MODEL = AttributeKey.stringKey("oryxos.model");
  private static final AttributeKey<String> ATTR_TOOL = AttributeKey.stringKey("oryxos.tool");
  private static final AttributeKey<Boolean> ATTR_BLOCKED =
      AttributeKey.booleanKey("oryxos.blocked_by_policy");

  private static final int TRACE_ID_HEX = 32;
  private static final int SPAN_ID_HEX = 16;

  /** turn 补记时把确定性 id 交给 SDK 的 IdGenerator（同线程同步调用，用后即清）。 */
  private static final class DeterministicIds implements IdGenerator {
    private final ThreadLocal<String> traceId = new ThreadLocal<>();
    private final ThreadLocal<String> spanId = new ThreadLocal<>();

    @Override
    public String generateTraceId() {
      String forced = traceId.get();
      return forced != null ? forced : IdGenerator.random().generateTraceId();
    }

    @Override
    public String generateSpanId() {
      String forced = spanId.get();
      return forced != null ? forced : IdGenerator.random().generateSpanId();
    }
  }

  private final DeterministicIds ids = new DeterministicIds();
  private final SdkTracerProvider provider;
  private final Tracer tracer;

  public OtelSpanRecorder(SpanProcessor processor, double samplerRatio) {
    this.provider =
        SdkTracerProvider.builder()
            .addSpanProcessor(processor)
            .setIdGenerator(ids)
            .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(samplerRatio)))
            .build();
    this.tracer = provider.get("oryxos");
  }

  @Override
  public void recordTurnSpan(
      String traceId,
      String agentName,
      String channel,
      boolean success,
      long startEpochMs,
      long durationMs) {
    String tid = normalize(traceId);
    if (tid == null) {
      return;
    }
    try {
      ids.traceId.set(tid);
      ids.spanId.set(tid.substring(0, SPAN_ID_HEX));
      Span span =
          tracer
              .spanBuilder("oryxos.turn")
              .setNoParent()
              .setStartTimestamp(startEpochMs, TimeUnit.MILLISECONDS)
              .setAttribute(ATTR_AGENT, nullSafe(agentName))
              .setAttribute(ATTR_CHANNEL, nullSafe(channel))
              .startSpan();
      endSpan(span, success, startEpochMs, durationMs);
    } catch (RuntimeException e) {
      LOG.debug("turn span 记录失败", e);
    } finally {
      ids.traceId.remove();
      ids.spanId.remove();
    }
  }

  @Override
  public void recordLlmSpan(
      String traceId,
      String providerName,
      String model,
      boolean success,
      long startEpochMs,
      long durationMs) {
    recordChild(
        traceId,
        "oryxos.llm_call",
        success,
        startEpochMs,
        durationMs,
        span ->
            span.setAttribute(ATTR_PROVIDER, nullSafe(providerName))
                .setAttribute(ATTR_MODEL, nullSafe(model)));
  }

  @Override
  public void recordToolSpan(
      String traceId,
      String toolName,
      boolean success,
      boolean blockedByPolicy,
      long startEpochMs,
      long durationMs) {
    recordChild(
        traceId,
        "oryxos.tool",
        success,
        startEpochMs,
        durationMs,
        span ->
            span.setAttribute(ATTR_TOOL, nullSafe(toolName))
                .setAttribute(ATTR_BLOCKED, blockedByPolicy));
  }

  private void recordChild(
      String traceId,
      String name,
      boolean success,
      long startEpochMs,
      long durationMs,
      java.util.function.Consumer<Span> attributes) {
    String tid = normalize(traceId);
    if (tid == null) {
      return;
    }
    try {
      SpanContext parent =
          SpanContext.createFromRemoteParent(
              tid, tid.substring(0, SPAN_ID_HEX), TraceFlags.getSampled(), TraceState.getDefault());
      Span span =
          tracer
              .spanBuilder(name)
              .setParent(Context.root().with(Span.wrap(parent)))
              .setStartTimestamp(startEpochMs, TimeUnit.MILLISECONDS)
              .startSpan();
      attributes.accept(span);
      endSpan(span, success, startEpochMs, durationMs);
    } catch (RuntimeException e) {
      // 静态消息（CRLF 纪律）；span 名见异常堆栈上下文
      LOG.debug("子 span 记录失败", e);
    }
  }

  private static void endSpan(Span span, boolean success, long startEpochMs, long durationMs) {
    if (!success) {
      span.setStatus(StatusCode.ERROR);
    }
    span.end(startEpochMs + Math.max(0, durationMs), TimeUnit.MILLISECONDS);
  }

  /** 021 traceId（UUID 含横线）→ OTel 32-hex TraceId；非法/空返回 null（该 span 静默跳过）。 */
  static String normalize(String traceId) {
    if (traceId == null) {
      return null;
    }
    String hex = traceId.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    if (hex.length() != TRACE_ID_HEX) {
      return null;
    }
    for (int i = 0; i < hex.length(); i++) {
      char c = hex.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!ok) {
        return null;
      }
    }
    // OTel 规范：全零 TraceId 非法
    return hex.chars().allMatch(c -> c == '0') ? null : hex;
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  /** 应用关闭：flush 并停导出（@Bean destroyMethod 自动识别 close）。 */
  @Override
  public void close() {
    try {
      provider.shutdown().join(2, TimeUnit.SECONDS);
    } catch (RuntimeException e) {
      LOG.debug("OTel provider 关闭异常", e);
    }
  }
}
