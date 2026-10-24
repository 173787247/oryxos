package io.oryxos.web.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SseWriter 单元：FR-008（断开后静默丢弃、处理继续）与心跳/关闭语义。 */
class SseWriterTest {

  @Test
  @DisplayName("构造时设置 SSE 响应头")
  void setsStreamingHeaders() throws IOException {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    HttpServletResponse response = responseOver(captured);

    new SseWriter(response, 60).close();

    verify(response).setContentType("text/event-stream;charset=UTF-8");
    verify(response).setHeader("Cache-Control", "no-cache");
  }

  @Test
  @DisplayName("event 按线协议写出 event/data/空行")
  void writesWireProtocolFrame() throws IOException {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (SseWriter writer = new SseWriter(responseOver(captured), 60)) {
      writer.event("token", "{\"text\":\"hi\"}");
    }

    assertEquals(
        "event: token\ndata: {\"text\":\"hi\"}\n\n", captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("写出失败后置 disconnected，后续事件静默丢弃")
  void dropsEventsAfterClientDisconnect() throws IOException {
    FailingAfterFirstWrite failing = new FailingAfterFirstWrite();
    try (SseWriter writer = new SseWriter(responseOver(failing), 60)) {
      assertFalse(writer.isDisconnected());
      writer.event("first", "{}");
      assertFalse(writer.isDisconnected());

      writer.event("second", "{}");
      assertTrue(writer.isDisconnected());

      writer.event("third", "{}");
    }

    assertEquals("event: first\ndata: {}\n\n", failing.captured());
  }

  @Test
  @DisplayName("close 之后事件不再写出且重复 close 安全")
  void dropsEventsAfterClose() throws IOException {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    SseWriter writer = new SseWriter(responseOver(captured), 60);
    writer.close();
    int sizeAfterClose = captured.size();

    writer.event("late", "{}");
    writer.close();

    assertEquals(sizeAfterClose, captured.size());
  }

  @Test
  @DisplayName("静默期按间隔写心跳注释；close 后心跳停止")
  void writesHeartbeatUntilClosed() throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    SseWriter writer = new SseWriter(responseOver(captured), 1);

    Thread.sleep(1500);
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains(": ping\n\n"));

    writer.close();
    int sizeAfterClose = captured.size();
    Thread.sleep(1200);
    assertEquals(sizeAfterClose, captured.size(), "close 之后不应再写心跳");
  }

  private static HttpServletResponse responseOver(ByteArrayOutputStream captured)
      throws IOException {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(servletStream(captured));
    return response;
  }

  private static HttpServletResponse responseOver(FailingAfterFirstWrite failing)
      throws IOException {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(failing);
    return response;
  }

  /** 只实现 write 路径的 ServletOutputStream，其余交给默认行为。 */
  private static ServletOutputStream servletStream(ByteArrayOutputStream captured) {
    return new ServletOutputStream() {
      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setWriteListener(WriteListener listener) {}

      @Override
      public void write(int b) {
        captured.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) {
        captured.write(b, off, len);
      }
    };
  }

  /** 第一次 write 成功，之后抛 IOException，模拟客户端断开。 */
  private static final class FailingAfterFirstWrite extends ServletOutputStream {
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private boolean failed;

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener listener) {}

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (failed) {
        throw new IOException("client disconnected");
      }
      failed = true;
      captured.write(b, off, len);
    }

    String captured() {
      return captured.toString(StandardCharsets.UTF_8);
    }
  }
}
