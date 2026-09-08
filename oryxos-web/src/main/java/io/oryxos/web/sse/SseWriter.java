package io.oryxos.web.sse;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步 SSE 写出器（019-sse-streaming R4）：在当前（虚拟）线程上阻塞写 {@link HttpServletResponse} 输出流并逐事件 flush——不用
 * SseEmitter/async servlet（宪法 VII：同步阻塞 + 虚拟线程）。
 *
 * <p>心跳：内部一条虚拟线程按固定间隔写 SSE 注释行 {@code : ping}（FR-007；固定间隔不做空闲重置，多发无害）， 与业务写共享同一把锁。写出 {@link
 * IOException}（客户端断开）→ 置 disconnected，后续事件静默丢弃、 处理照常进行（FR-008：断开不产生残缺会话状态）；{@link #close()}
 * 后心跳线程退出。
 *
 * <p>线协议：{@code event:} 行 + {@code data:} 单行 JSON + 空行（contracts/sse-protocol.md §2）。
 */
public final class SseWriter implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SseWriter.class);

  private static final String HEARTBEAT_LINE = ": ping\n\n";

  private final OutputStream out;
  private final Object lock = new Object();
  private final Thread heartbeat;
  private volatile boolean disconnected;
  private volatile boolean closed;

  public SseWriter(HttpServletResponse response, long heartbeatSeconds) throws IOException {
    response.setContentType("text/event-stream;charset=UTF-8");
    response.setHeader("Cache-Control", "no-cache");
    this.out = response.getOutputStream();
    out.flush(); // 先把响应头刷出去，客户端立刻确认流已建立
    this.heartbeat =
        Thread.ofVirtual()
            .name("sse-heartbeat")
            .start(
                () -> {
                  while (!closed && !disconnected) {
                    try {
                      Thread.sleep(java.time.Duration.ofSeconds(heartbeatSeconds));
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    writeRaw(HEARTBEAT_LINE);
                  }
                });
  }

  /** 写一个业务事件并 flush；已关闭/已断开则静默丢弃（FR-008）。 */
  public void event(String type, String payloadJson) {
    writeRaw("event: " + type + "\n" + "data: " + payloadJson + "\n\n");
  }

  /** 客户端是否已断开（写出失败过）。 */
  public boolean isDisconnected() {
    return disconnected;
  }

  @Override
  public void close() {
    closed = true;
    heartbeat.interrupt();
    synchronized (lock) {
      try {
        out.flush();
      } catch (IOException e) {
        disconnected = true;
      }
    }
  }

  private void writeRaw(String frame) {
    synchronized (lock) {
      if (closed || disconnected) {
        return;
      }
      try {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
      } catch (IOException e) {
        // 客户端断开：停止推送但不上抛——本轮处理继续跑完并落库（FR-008）
        disconnected = true;
        LOG.debug("SSE client disconnected, further events dropped", e);
      }
    }
  }
}
