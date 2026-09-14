package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 039 US3：traceId 同源映射、确定性父子（turn spanId = traceId 前 16 hex）、显式起止时间、 非法 traceId
 * 静默跳过、端点不可达不阻塞（analyze A1，BatchSpanProcessor 缓冲语义）。
 */
class OtelSpanRecorderTest {

  private static final String UUID_TRACE = "0af7651b-16f9-4342-8b21-7d97c1a3b105";
  private static final String HEX_TRACE = "0af7651b16f943428b217d97c1a3b105";
  private static final String ROOT_SPAN = HEX_TRACE.substring(0, 16);

  @Test
  void turnAndChildrenFormOneTraceWithDeterministicParent() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    try (OtelSpanRecorder recorder =
        new OtelSpanRecorder(SimpleSpanProcessor.create(exporter), 1.0)) {
      long start = 1_700_000_000_000L;
      recorder.recordTurnSpan(UUID_TRACE, "default", "cli", true, start, 5_000);
      recorder.recordLlmSpan(UUID_TRACE, "deepseek", "deepseek-chat", true, start + 100, 2_000);
      recorder.recordToolSpan(UUID_TRACE, "read_file", false, false, start + 2_500, 300);

      List<SpanData> spans = exporter.getFinishedSpanItems();
      assertThat(spans).hasSize(3);
      Map<String, SpanData> byName =
          spans.stream().collect(java.util.stream.Collectors.toMap(SpanData::getName, s -> s));

      SpanData turn = byName.get("oryxos.turn");
      assertThat(turn.getTraceId()).isEqualTo(HEX_TRACE); // 021 UUID 去横线同源
      assertThat(turn.getSpanId()).isEqualTo(ROOT_SPAN); // 确定性根 spanId
      assertThat(turn.getStartEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(start));
      assertThat(turn.getEndEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(start + 5_000));

      for (String child : List.of("oryxos.llm_call", "oryxos.tool")) {
        SpanData s = byName.get(child);
        assertThat(s.getTraceId()).isEqualTo(HEX_TRACE);
        assertThat(s.getParentSpanId()).isEqualTo(ROOT_SPAN); // 乱序补记仍拼出正确父子
        assertThat(s.getSpanId()).isNotEqualTo(ROOT_SPAN);
      }
      assertThat(byName.get("oryxos.tool").getStatus().getStatusCode())
          .isEqualTo(StatusCode.ERROR); // 失败置 ERROR
      assertThat(byName.get("oryxos.llm_call").getStatus().getStatusCode())
          .isNotEqualTo(StatusCode.ERROR);
    }
  }

  @Test
  void invalidTraceIdIsSkippedSilently() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    try (OtelSpanRecorder recorder =
        new OtelSpanRecorder(SimpleSpanProcessor.create(exporter), 1.0)) {
      recorder.recordTurnSpan(null, "a", "cli", true, 1, 1);
      recorder.recordTurnSpan("not-a-trace", "a", "cli", true, 1, 1);
      recorder.recordLlmSpan("short", "p", "m", true, 1, 1);
      recorder.recordToolSpan("00000000-0000-0000-0000-000000000000", "t", true, false, 1, 1);
      assertThat(exporter.getFinishedSpanItems()).isEmpty(); // 静默跳过不抛
    }
  }

  @Test
  void normalizeMapsUuidAndRejectsGarbage() {
    Function<String, String> n = OtelSpanRecorder::normalize;
    assertThat(n.apply(UUID_TRACE)).isEqualTo(HEX_TRACE);
    assertThat(n.apply(HEX_TRACE)).isEqualTo(HEX_TRACE);
    assertThat(n.apply("ZZf7651b16f943428b217d97c1a3b105")).isNull();
    assertThat(n.apply("")).isNull();
  }

  @Test
  void unreachableEndpointDoesNotBlockRecordCalls() {
    // analyze A1：端点无人监听——BatchSpanProcessor 缓冲/丢弃，record 与 close 都不得阻塞主链路
    io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter exporter =
        io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://127.0.0.1:1")
            .setTimeout(java.time.Duration.ofMillis(200))
            .build();
    long begin = System.nanoTime();
    try (OtelSpanRecorder recorder =
        new OtelSpanRecorder(
            io.opentelemetry.sdk.trace.export.BatchSpanProcessor.builder(exporter).build(), 1.0)) {
      for (int i = 0; i < 50; i++) {
        recorder.recordTurnSpan(UUID_TRACE, "a", "cli", true, i, 1);
      }
    }
    long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
    assertThat(elapsedMs).isLessThan(5_000); // record 循环 + close(≤2s join) 均有界
  }
}
