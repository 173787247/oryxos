package io.oryxos.core.channel;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用 {@link WebSocket}：记录发出的帧，不触碰网络。
 *
 * <p>存在的理由与 {@link StubHttpClient} 相同。网关客户端把端点与连接建立写死在方法内部，没有可注入的接缝； 但它们都 {@code implements
 * WebSocket.Listener}，而回调签名接收一个 {@link WebSocket} 参数 —— 于是喂一个假的 socket 就能驱动整个协议处理：Hello →
 * Identify、心跳、Dispatch 分发、以及关闭的分类。
 *
 * <p>{@code sendText} 记录正文；{@code sendClose} 记录关闭码（断言「优雅关闭用 NORMAL_CLOSURE」用）； {@code request}
 * 计数（断言「处理完一帧继续要下一帧」用）—— 少了这个调用，长连接会静默地不再收帧。
 */
public final class StubWebSocket implements WebSocket {

  private final List<String> sentText = new CopyOnWriteArrayList<>();
  private final List<Integer> closeCodes = new CopyOnWriteArrayList<>();
  private final AtomicInteger requestedFrames = new AtomicInteger();
  private final AtomicBoolean aborted = new AtomicBoolean(false);
  private final AtomicReference<Boolean> outputClosed = new AtomicReference<>(false);

  /** 发出的文本帧，按先后顺序。 */
  public List<String> sentText() {
    return List.copyOf(sentText);
  }

  /** 最后一次发出的文本帧；没发过则为 {@code null}。 */
  public String lastText() {
    return sentText.isEmpty() ? null : sentText.get(sentText.size() - 1);
  }

  /** 发出的关闭码，按先后顺序。 */
  public List<Integer> closeCodes() {
    return List.copyOf(closeCodes);
  }

  /** {@link #request(long)} 的累计请求帧数。 */
  public int requestedFrames() {
    return requestedFrames.get();
  }

  /** 是否被 {@link #abort()} 过。 */
  public boolean aborted() {
    return aborted.get();
  }

  @Override
  public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
    sentText.add(data == null ? "" : data.toString());
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
    closeCodes.add(statusCode);
    outputClosed.set(true);
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public void request(long n) {
    requestedFrames.addAndGet((int) Math.max(0L, n));
  }

  @Override
  public String getSubprotocol() {
    return "";
  }

  @Override
  public boolean isOutputClosed() {
    return outputClosed.get();
  }

  @Override
  public boolean isInputClosed() {
    return false;
  }

  @Override
  public void abort() {
    aborted.set(true);
  }
}
