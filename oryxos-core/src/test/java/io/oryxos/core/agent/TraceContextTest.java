package io.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceContextTest {

  @AfterEach
  void cleanup() {
    // 测试线程复用：无条件置入 owner=true 再关闭，确保 ThreadLocal 与 MDC 都被清空
    TraceContext.open("cleanup").close();
  }

  @Test
  void open生成UUID且MDC同步() {
    try (TraceContext.Scope scope = TraceContext.openIfAbsent()) {
      assertThat(scope.isOwner()).isTrue();
      assertThat(scope.traceId()).isNotBlank();
      assertThat(TraceContext.current()).isEqualTo(scope.traceId());
      assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo(scope.traceId());
    }
  }

  @Test
  void 嵌套openIfAbsent复用不覆盖_内层close不清外层() {
    try (TraceContext.Scope outer = TraceContext.openIfAbsent()) {
      try (TraceContext.Scope inner = TraceContext.openIfAbsent()) {
        assertThat(inner.isOwner()).isFalse();
        assertThat(inner.traceId()).isEqualTo(outer.traceId());
      }
      // 内层 close 后外层上下文必须仍在（AgentService 兜底 open 不得清掉 controller 先开的 trace）
      assertThat(TraceContext.current()).isEqualTo(outer.traceId());
      assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo(outer.traceId());
    }
  }

  @Test
  void owner关闭后ThreadLocal与MDC均空() {
    String traceId;
    try (TraceContext.Scope scope = TraceContext.openIfAbsent()) {
      traceId = scope.traceId();
    }
    assertThat(traceId).isNotBlank();
    assertThat(TraceContext.current()).isNull();
    assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
  }

  @Test
  void 显式open用外部ID置入_跨线程传递语义() {
    try (TraceContext.Scope scope = TraceContext.open("trace-from-caller")) {
      assertThat(scope.isOwner()).isTrue();
      assertThat(TraceContext.current()).isEqualTo("trace-from-caller");
      assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo("trace-from-caller");
    }
    assertThat(TraceContext.current()).isNull();
  }

  @Test
  void 多线程各自独立互不可见() throws Exception {
    AtomicReference<String> otherThreadTrace = new AtomicReference<>();
    AtomicReference<String> otherThreadOwnTrace = new AtomicReference<>();
    try (TraceContext.Scope scope = TraceContext.openIfAbsent()) {
      CountDownLatch done = new CountDownLatch(1);
      Thread.ofVirtual()
          .start(
              () -> {
                otherThreadTrace.set(TraceContext.current());
                try (TraceContext.Scope own = TraceContext.openIfAbsent()) {
                  otherThreadOwnTrace.set(own.traceId());
                }
                done.countDown();
              })
          .join();
      done.await();
      assertThat(otherThreadTrace.get()).isNull(); // 新线程看不见本线程的 trace
      assertThat(otherThreadOwnTrace.get()).isNotEqualTo(scope.traceId()); // 各开各的
      assertThat(TraceContext.current()).isEqualTo(scope.traceId()); // 本线程不受影响
    }
  }
}
