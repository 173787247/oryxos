package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 023 US3：五类 oryxos_* 指标名/标签/计数 + FR-010 埋点异常静默。 */
class MicrometerMetricsRecorderTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

  @Test
  void llm调用_计数与耗时按维度落表() {
    recorder.recordLlmCall("deepseek", "deepseek-chat", true, 1200);
    recorder.recordLlmCall("deepseek", "deepseek-chat", false, 30);

    assertThat(
            registry
                .counter(
                    "oryxos_llm_calls_total",
                    "provider",
                    "deepseek",
                    "model",
                    "deepseek-chat",
                    "outcome",
                    "success")
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .counter(
                    "oryxos_llm_calls_total",
                    "provider",
                    "deepseek",
                    "model",
                    "deepseek-chat",
                    "outcome",
                    "failure")
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .timer(
                    "oryxos_llm_call_duration_seconds",
                    "provider",
                    "deepseek",
                    "model",
                    "deepseek-chat")
                .count())
        .isEqualTo(2);
  }

  @Test
  void token计数_按类型累加_空值跳过() {
    recorder.recordLlmTokens("deepseek", "m", 812, 64);
    recorder.recordLlmTokens("deepseek", "m", null, null); // 空值不计不抛

    assertThat(
            registry
                .counter(
                    "oryxos_llm_tokens_total",
                    "provider",
                    "deepseek",
                    "model",
                    "m",
                    "type",
                    "prompt")
                .count())
        .isEqualTo(812);
    assertThat(
            registry
                .counter(
                    "oryxos_llm_tokens_total",
                    "provider",
                    "deepseek",
                    "model",
                    "m",
                    "type",
                    "completion")
                .count())
        .isEqualTo(64);
  }

  @Test
  void 工具与策略与切换_计数在位() {
    recorder.recordToolInvocation("save_memory", true);
    recorder.recordToolInvocation("shell", false);
    recorder.recordPolicyBlock("shell");
    recorder.recordFallbackSwitch("broken", "mock");

    assertThat(
            registry
                .counter(
                    "oryxos_tool_invocations_total", "tool", "save_memory", "outcome", "success")
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .counter("oryxos_tool_invocations_total", "tool", "shell", "outcome", "failure")
                .count())
        .isEqualTo(1);
    assertThat(registry.counter("oryxos_policy_blocks_total", "tool", "shell").count())
        .isEqualTo(1);
    assertThat(
            registry
                .counter("oryxos_fallback_switches_total", "from", "broken", "to", "mock")
                .count())
        .isEqualTo(1);
  }

  @Test
  void null标签值_兜底为unknown() {
    recorder.recordLlmCall(null, "", true, 1);
    assertThat(
            registry
                .counter(
                    "oryxos_llm_calls_total",
                    "provider",
                    "unknown",
                    "model",
                    "unknown",
                    "outcome",
                    "success")
                .count())
        .isEqualTo(1);
  }

  @Test
  void 恒抛异常的registry_五方法全部静默不抛() {
    MeterRegistry broken =
        Mockito.mock(
            MeterRegistry.class,
            invocation -> {
              throw new IllegalStateException("registry down");
            });
    MicrometerMetricsRecorder faulty = new MicrometerMetricsRecorder(broken);

    // FR-010 显式断言：任何指标故障不得影响主链路
    assertThatCode(
            () -> {
              faulty.recordLlmCall("p", "m", true, 1);
              faulty.recordLlmTokens("p", "m", 1, 1);
              faulty.recordToolInvocation("t", true);
              faulty.recordPolicyBlock("t");
              faulty.recordFallbackSwitch("a", "b");
            })
        .doesNotThrowAnyException();
  }
}
